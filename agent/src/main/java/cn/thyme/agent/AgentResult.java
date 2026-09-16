package cn.thyme.agent;

/**
 * Agent 执行结果：最终文本、实际轮次、累计 token、停止原因，
 * 以及本次运行所属的会话 id（回传调用方，用于把后续请求接回同一会话）。
 *
 * <p>契约：{@code content} <b>只承载模型真实输出的文本</b>。未正常完成时没有最终
 * 答案，{@code content} 为 {@code null}；是否被打断以及为何被打断一律看
 * {@link #stopReason()}，提示文案由前端渲染。</p>
 *
 * <p>{@code totalTokens} 为<b>整棵子树</b>的累计用量（含子 agent），与
 * {@code maxTokens} 是同一把尺子。</p>
 *
 * <p>另：模型无工具调用且无文本时由 {@link DefaultAgent} 抛
 * {@link EmptyModelResponseException}，不会落到这里成为空串。</p>
 */
public record AgentResult(String content, int iterations, int totalTokens,
                          AgentStopReason stopReason, String sessionId) {

    public AgentResult {
        if (stopReason == null) {
            stopReason = AgentStopReason.COMPLETED;
        }
        if (stopReason.isTruncated() && content != null) {
            throw new IllegalArgumentException("被中断时不应有最终内容: " + stopReason);
        }
        if (!stopReason.isTruncated() && (content == null || content.isBlank())) {
            throw new IllegalArgumentException("正常结束时必须有非空内容");
        }
    }

    /** 是否因上限被中断（没有最终答案）。 */
    public boolean truncated() {
        return stopReason.isTruncated();
    }

    /** 被上限中断（没有最终答案）。 */
    public static AgentResult truncated(int iterations, int totalTokens,
                                        AgentStopReason stopReason, String sessionId) {
        if (stopReason == AgentStopReason.COMPLETED) {
            throw new IllegalArgumentException("中断原因不能是 COMPLETED");
        }
        return new AgentResult(null, iterations, totalTokens, stopReason, sessionId);
    }

    /** 正常结束，带模型的最终答案。 */
    public static AgentResult completed(String content, int iterations, int totalTokens, String sessionId) {
        return new AgentResult(content, iterations, totalTokens, AgentStopReason.COMPLETED, sessionId);
    }
}
