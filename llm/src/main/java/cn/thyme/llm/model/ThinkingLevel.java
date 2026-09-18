package cn.thyme.llm.model;

/**
 * 深度思考档位。
 *
 * <p>{@link #NONE} 表示<b>显式关闭</b>：请求体里带上 {@code reasoning_effort: "none"}。
 * 这和「不设置 thinking」（null）是两回事——后者只是不下发该参数，模型于是按自己的
 * 默认值走，而推理模型的默认值是开。Ollama 上的 qwen3.5 就是这样：不指定时它把全部
 * {@code max_tokens} 花在 reasoning 上，{@code content} 返回空串、{@code finish_reason}
 * 是 {@code length}，调用方拿到一个没有任何错误的空回答。</p>
 *
 * <p>需要「确定不要推理」的调用方（例如标题生成）必须显式用 {@link #NONE}。</p>
 */
public enum ThinkingLevel {

    /** 显式关闭推理。 */
    NONE,

    LOW,

    MEDIUM,

    HIGH
}
