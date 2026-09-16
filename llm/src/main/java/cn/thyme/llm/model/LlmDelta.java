package cn.thyme.llm.model;

/**
 * 模型流式产出的一个增量片段。一次 {@code chat} 会回调多次，最终由
 * {@link LlmResponse} 给出完整结果——增量只用于实时展示，不作为结果来源。
 *
 * <p>回答文本与思考过程走同一个回调，用 {@link Kind} 区分：调用方可能只想展示
 * 其中之一，也可能对两者做不同的呈现（例如思考折叠、回答逐字）。</p>
 */
public record LlmDelta(Kind kind, String text) {

    public enum Kind {
        /** 回答正文。 */
        CONTENT,
        /** 思考过程（reasoning）。模型未开启深度思考时不会出现。 */
        REASONING
    }

    public static LlmDelta content(String text) {
        return new LlmDelta(Kind.CONTENT, text);
    }

    public static LlmDelta reasoning(String text) {
        return new LlmDelta(Kind.REASONING, text);
    }

    /** 是否为空片段（厂商会发纯空串的心跳块，调用方一般应跳过）。 */
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }
}
