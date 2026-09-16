package cn.thyme.agent;

/**
 * 一次运行的停止原因，取代原先的 {@code truncated} 布尔：调用方需要区分
 * 「正常完成」与各种「被上限打断」——前者有最终答案，后者没有。
 * 提示文案由前端按本枚举自行渲染，不进 {@link AgentResult#content()}。
 */
public enum AgentStopReason {

    /** 模型给出了最终答案，正常结束。 */
    COMPLETED,

    /** 达到轮次上限。 */
    MAX_ITERATIONS,

    /** 累计 token 预算已耗尽（每轮工具执行后检查）。 */
    TOKEN_BUDGET,

    /** 预计本次调用的输入本身就超出预算，未发起该次调用。 */
    INPUT_BUDGET_EXCEEDED,

    /**
     * 被调用方主动中断（用户点了停止、连接断开）。
     *
     * <p>半截回答一律丢弃：中断可能发生在任意一个增量之间，拼出来的残句既不是
     * 模型的完整输出，也会在下次回注历史时被当成一轮正常回答。</p>
     */
    CANCELLED;

    /** 是否因上限被打断（此时没有最终答案）。 */
    public boolean isTruncated() {
        return this != COMPLETED;
    }
}
