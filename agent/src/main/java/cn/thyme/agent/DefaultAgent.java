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
        // 父级传入的预算（共享全树用量）；顶层运行没有父级，就按本 agent 的 maxTokens 开一个
        AgentBudget budget = config.budget() == null
                ? new AgentBudget(config.maxTokens())
                : config.budget();
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
            List<LlmToolDefinition> toolDefinitions = toToolDefinitions();

            while (iterations < config.maxIterations()) {
                // 调用前先估本次输入体积：单次调用的输入本身就可能超预算
                // （历史很长、工具输出很大），光看已发生的用量拦不住「一轮烧干」。
                // 不豁免首次调用——宁可空手而回，也不发出一开始就注定超额的请求。
                int inputTokens = estimateInputTokens(messages, toolDefinitions);
                if (inputTokens > budget.remaining()) {
                    return finish(publisher, sessionId,
                            AgentResult.truncated(iterations, budget.used(),
                                    AgentStopReason.INPUT_BUDGET_EXCEEDED, sessionId));
                }

                iterations++;

                LlmRequest request = LlmRequest.builder()
                        .model(config.model())
                        .messages(messages)
                        .tools(toolDefinitions)
                        .temperature(config.temperature())
                        .build();

                LlmResponse response = config.llmClient().chat(request);

                budget.spend(usageOf(response, messages));

                if (!response.hasToolCalls()) {
                    String content = response.content();
                    if (content == null || content.isBlank()) {
                        // 模型既没调工具也没给文本：不能静默当成空答案返回，
                        // 否则调用方无法区分「模型没输出」和「模型正常完成」。
                        throw new EmptyModelResponseException(
                                "模型返回空内容（第 " + iterations + " 轮：既无工具调用也无文本）");
                    }
                    publisher.publish(new AgentEvent.Message(sessionId, Instant.now(), LlmRole.ASSISTANT, content));
                    return finish(publisher, sessionId,
                            AgentResult.completed(content, iterations, budget.used(), sessionId));
                }

                messages.add(LlmMessage.assistantToolCall(response.toolCalls()));

                List<LlmToolResult> results = new ArrayList<>();
                for (LlmToolCall call : response.toolCalls()) {
                    publisher.publish(new AgentEvent.ToolCall(
                            sessionId, Instant.now(), call.id(), call.name(), call.arguments()));
                    // 工具作用于「工作区会话」：子 agent 因此仍能看到父会话的文件；
                    // 事件与历史仍挂在 sessionId 上，保持上下文隔离
                    ToolResult result = executeTool(call, context.workspaceSessionId(), budget);
                    publisher.publish(new AgentEvent.ToolResult(
                            sessionId, Instant.now(), call.id(), call.name(), result.text(), result.ok()));
                    results.add(new LlmToolResult(call.id(), result.text(), !result.ok()));
                }
                messages.add(LlmMessage.toolResult(results));

                if (budget.remaining() <= 0) {
                    return finish(publisher, sessionId, AgentResult.truncated(
                            iterations, budget.used(), AgentStopReason.TOKEN_BUDGET, sessionId));
                }
            }

            return finish(publisher, sessionId, AgentResult.truncated(
                    iterations, budget.used(), AgentStopReason.MAX_ITERATIONS, sessionId));
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

    /**
     * 本次调用的输入体积估算：chars/4 ≈ tokens（与 {@link #usageOf} 的字符口径一致）。
     *
     * <p>工具定义也计入——它们的体积固定但不可忽略。工具结果同样计入，且是最容易
     * 漏掉的一块：它挂在 {@link LlmMessage#toolResults()} 上，{@code content} 为 null，
     * 只累计 content 会把它整个跳过，恰恰漏掉了上下文里增长最快、体积最大的部分。</p>
     */
    private int estimateInputTokens(List<LlmMessage> messages, List<LlmToolDefinition> tools) {
        int chars = messages.stream().mapToInt(DefaultAgent::messageChars).sum();
        for (LlmToolDefinition tool : tools) {
            chars += tool.name().length() + tool.description().length();
        }
        return chars / 4;
    }

    private int usageOf(LlmResponse response, List<LlmMessage> messages) {
        if (response.usage() != null) {
            return response.usage().totalTokens();
        }
        int inputChars = messages.stream().mapToInt(DefaultAgent::messageChars).sum();
        int outputChars = response.content() == null ? 0 : response.content().length();
        return (inputChars + outputChars) / 4;
    }

    /** 一条消息承载的全部字符（含文本、工具调用参数与工具执行结果）。 */
    private static int messageChars(LlmMessage message) {
        int chars = message.content() == null ? 0 : message.content().length();
        for (LlmToolResult result : message.toolResults()) {
            chars += result.content() == null ? 0 : result.content().length();
        }
        return chars;
    }

    private List<LlmToolDefinition> toToolDefinitions() {
        return config.tools().stream()
                .map(tool -> new LlmToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    private ToolResult executeTool(LlmToolCall call, String sessionId, AgentBudget budget) {
        ToolContext context = new ToolContext(sessionId, budget);
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
