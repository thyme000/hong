package cn.thyme.llm.model;

/**
 * token 用量：输入与输出 token 数。厂商未返回时可为空，
 * 此时可调用带文本的重载，按字符粗略估算。
 */
public record LlmUsage(Integer inputTokens, Integer outputTokens) {

    /** 总 token 数，未返回的按 0 计。 */
    public int totalTokens() {
        return orZero(inputTokens) + orZero(outputTokens);
    }

    /**
     * 总 token 数，未返回的按字符粗略估算。
     *
     * @param inputText  输入文本（inputTokens 为空时按字符估算）
     * @param outputText 输出文本（outputTokens 为空时按字符估算）
     */
    public int totalTokens(String inputText, String outputText) {
        return (inputTokens == null ? estimate(inputText) : inputTokens)
                + (outputTokens == null ? estimate(outputText) : outputTokens);
    }

    /** 按字符粗略估算 token 数：约等于字符数。 */
    public static int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : text.length();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
