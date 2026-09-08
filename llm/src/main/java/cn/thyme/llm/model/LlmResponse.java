package cn.thyme.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次 LLM 响应：模型、文本内容（工具调用时可为空）、工具调用列表、token 用量。
 */
public record LlmResponse(
        String model,
        String content,
        List<LlmToolCall> toolCalls,
        LlmUsage usage) {

    public LlmResponse {
        Objects.requireNonNull(model, "model must not be null");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 是否包含工具调用（此时应执行工具并回填结果，而非直接取 content）。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
