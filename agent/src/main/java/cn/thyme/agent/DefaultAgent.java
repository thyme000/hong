package cn.thyme.agent;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmRole;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmToolDefinition;
import cn.thyme.llm.model.LlmToolResult;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的默认实现：基础 agent loop。
 * 构造请求（system + 历史 + task + 工具定义）→ 调 LLM → 若有工具调用则执行并回填
 * → 继续循环，直到 LLM 给出最终答案，或达到轮次 / token 上限。
 * 运行过程中通过 {@link AgentEventPublisher} 发出分阶段事件。
 */
public class DefaultAgent implements Agent {

    private final AgentConfig config;
    private final PromptRenderer renderer = new PromptRenderer();

    public DefaultAgent(AgentConfig config) {
        this.config = config;
    }

    @Override
    public AgentResult run(AgentContext context) {
        AgentEventPublisher publisher = config.eventPublisher();
        String sessionId = context.sessionId();
        publisher.begin();
        try {
            publisher.publish(new AgentEvent.Started(sessionId, Instant.now(), context.task()));

            List<LlmMessage> messages = new ArrayList<>();
            String system = buildSystem(context);
            if (!system.isEmpty()) {
                messages.add(LlmMessage.system(system));
            }
            messages.addAll(context.history());
            if (context.task() != null && !context.task().isEmpty()) {
                messages.add(LlmMessage.user(context.task()));
                publisher.publish(new AgentEvent.Message(sessionId, Instant.now(), LlmRole.USER, context.task()));
            }

            int iterations = 0;
            int totalTokens = 0;

            while (iterations < config.maxIterations()) {
                iterations++;

                LlmRequest request = LlmRequest.builder()
                        .model(config.model())
                        .messages(messages)
                        .tools(toToolDefinitions())
                        .temperature(config.temperature())
                        .build();

                LlmResponse response = config.llmClient().chat(request);

                totalTokens += estimateTokens(response, messages);

                if (!response.hasToolCalls()) {
                    String content = response.content() == null ? "" : response.content();
                    publisher.publish(new AgentEvent.Message(sessionId, Instant.now(), LlmRole.ASSISTANT, content));
                    return finish(publisher, sessionId, new AgentResult(content, iterations, totalTokens, false,
                            sessionId));
                }

                messages.add(LlmMessage.assistantToolCall(response.toolCalls()));

                List<LlmToolResult> results = new ArrayList<>();
                for (LlmToolCall call : response.toolCalls()) {
                    publisher.publish(new AgentEvent.ToolCall(
                            sessionId, Instant.now(), call.id(), call.name(), call.arguments()));
                    ToolResult result = executeTool(call, sessionId);
                    publisher.publish(new AgentEvent.ToolResult(
                            sessionId, Instant.now(), call.id(), call.name(), result.text(), result.ok()));
                    results.add(new LlmToolResult(call.id(), result.text(), !result.ok()));
                }
                messages.add(LlmMessage.toolResult(results));

                if (totalTokens >= config.maxTokens()) {
                    return finish(publisher, sessionId,
                            new AgentResult("达到 token 上限", iterations, totalTokens, true, sessionId));
                }
            }

            return finish(publisher, sessionId,
                    new AgentResult("达到轮次上限", iterations, totalTokens, true, sessionId));
        } finally {
            publisher.end();
        }
    }

    private AgentResult finish(AgentEventPublisher publisher, String sessionId, AgentResult result) {
        publisher.publish(new AgentEvent.Finished(sessionId, Instant.now(), result));
        return result;
    }

    private String buildSystem(AgentContext context) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("global", context.global() == null ? "" : context.global());
        variables.put("task", context.task() == null ? "" : context.task());
        variables.put("model", config.model());
        variables.put("agentName", config.name());
        variables.put("tools", formatTools(config.tools()));
        return renderer.render(config.systemPrompt(), variables);
    }

    private String formatTools(List<Tool> tools) {
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }

    private int estimateTokens(LlmResponse response, List<LlmMessage> messages) {
        if (response.usage() != null) {
            return response.usage().totalTokens();
        }
        int inputChars = messages.stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length())
                .sum();
        int outputChars = response.content() == null ? 0 : response.content().length();
        return inputChars + outputChars;
    }

    private List<LlmToolDefinition> toToolDefinitions() {
        return config.tools().stream()
                .map(tool -> new LlmToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    private ToolResult executeTool(LlmToolCall call, String sessionId) {
        ToolContext context = new ToolContext(sessionId);
        for (Tool tool : config.tools()) {
            if (tool.name().equals(call.name())) {
                try {
                    return tool.execute(call.arguments(), context);
                } catch (Exception e) {
                    return ToolResult.failure(e.getMessage());
                }
            }
        }
        return ToolResult.failure("未找到工具: " + call.name());
    }
}
