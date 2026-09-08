package cn.thyme.agent;

import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmToolDefinition;
import cn.thyme.llm.model.LlmToolResult;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 的默认实现：基础 agent loop。
 * 构造请求（system + 历史 + task + 工具定义）→ 调 LLM → 若有工具调用则执行并回填
 * → 继续循环，直到 LLM 给出最终答案，或达到轮次 / token 上限。
 */
public class DefaultAgent implements Agent {

    private final AgentConfig config;

    public DefaultAgent(AgentConfig config) {
        this.config = config;
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<LlmMessage> messages = new ArrayList<>();
        String system = buildSystem(context);
        if (!system.isEmpty()) {
            messages.add(LlmMessage.system(system));
        }
        messages.addAll(context.history());
        if (context.task() != null && !context.task().isEmpty()) {
            messages.add(LlmMessage.user(context.task()));
        }

        int iterations = 0;
        int totalTokens = 0;

        while (iterations < config.maxIterations()) {
            iterations++;

            LlmRequest request = LlmRequest.builder()
                    .model(config.model())
                    .messages(messages)
                    .tools(toToolDefinitions())
                    .build();

            LlmResponse response = config.llmClient().chat(request);

            totalTokens += estimateTokens(response, messages);

            if (!response.hasToolCalls()) {
                String content = response.content() == null ? "" : response.content();
                return new AgentResult(content, iterations, totalTokens, false);
            }

            messages.add(LlmMessage.assistantToolCall(response.toolCalls()));

            List<LlmToolResult> results = new ArrayList<>();
            for (LlmToolCall call : response.toolCalls()) {
                ToolResult result = executeTool(call);
                results.add(new LlmToolResult(call.id(), result.text(), !result.ok()));
            }
            messages.add(LlmMessage.toolResult(results));

            if (totalTokens >= config.maxTokens()) {
                return new AgentResult("达到 token 上限", iterations, totalTokens, true);
            }
        }

        return new AgentResult("达到轮次上限", iterations, totalTokens, true);
    }

    private String buildSystem(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.global() != null && !context.global().isEmpty()) {
            sb.append(context.global());
        }
        if (config.systemPrompt() != null && !config.systemPrompt().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(config.systemPrompt());
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

    private ToolResult executeTool(LlmToolCall call) {
        for (Tool tool : config.tools()) {
            if (tool.name().equals(call.name())) {
                try {
                    return tool.execute(call.arguments());
                } catch (Exception e) {
                    return ToolResult.failure(e.getMessage());
                }
            }
        }
        return ToolResult.failure("未找到工具: " + call.name());
    }
}
