package cn.thyme.llm.model;

import java.util.Objects;

/**
 * 一次工具执行结果，回填给 LLM：关联的工具调用 id、结果文本、是否出错。
 */
public record LlmToolResult(String toolCallId, String content, boolean isError) {

    public LlmToolResult {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
