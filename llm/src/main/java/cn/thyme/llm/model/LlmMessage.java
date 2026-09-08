package cn.thyme.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * 一条对话消息。既可以是纯文本（content 非空），也可以是工具消息：
 * assistant 的 toolCalls（要调用哪些工具）或 user 的 toolResults（工具执行结果）。
 */
public record LlmMessage(
        LlmRole role,
        String content,
        List<LlmToolCall> toolCalls,
        List<LlmToolResult> toolResults) {

    public LlmMessage {
        Objects.requireNonNull(role, "role must not be null");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    public static LlmMessage of(LlmRole role, String content) {
        return new LlmMessage(role, content, null, null);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content, null, null);
    }

    /** assistant 请求调用工具。 */
    public static LlmMessage assistantToolCall(List<LlmToolCall> toolCalls) {
        return new LlmMessage(LlmRole.ASSISTANT, null, toolCalls, null);
    }

    /** user 回填工具执行结果。 */
    public static LlmMessage toolResult(List<LlmToolResult> toolResults) {
        return new LlmMessage(LlmRole.USER, null, null, toolResults);
    }
}
