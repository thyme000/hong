package cn.thyme.tool;

/**
 * 工具的统一执行结果：主文本 + 可选的原始/结构化内容，以及是否成功。
 * 文本可直接拼进 LLM 上下文；原始内容保留给调用方做进一步处理。
 */
public record ToolResult(String text, Object content, boolean ok) {

    public static ToolResult success(String text) {
        return new ToolResult(text, null, true);
    }

    public static ToolResult success(String text, Object content) {
        return new ToolResult(text, content, true);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(message, null, false);
    }
}
