package cn.thyme.agent;

/**
 * 一次运行的 token 预算：agent 循环与嵌套的子 agent 共享同一份<b>累计用量</b>，
 * 使整棵委派树的消耗受同一个上限约束。
 *
 * <p>语义是「划拨」：父 agent 用 {@link #fork(int)} 从剩余额度里切一块给子 agent，
 * 子 agent 的上限就被钉死在「划拨时刻的已用量 + 划拨量」上，看不到父 agent 的剩余额度。
 * 子 agent 提前结束时，没花掉的额度自然还在池子里（因为共享的是<b>实际用量</b>，
 * 不是预留量），父 agent 可直接继续用。</p>
 *
 * <p>两个上限同时生效，取小者：</p>
 * <ul>
 *   <li>本 agent 自己的 {@code limit}（父级划拨的额度）——防止单个子 agent 吃掉全部预算</li>
 *   <li>全树总量 {@code shared.limit}——防止并发子 agent 合计烧穿父 agent 的池子</li>
 * </ul>
 *
 * <p>线程安全：工具可能在线程池上执行，多个子 agent 会与父 agent 并发读写同一计数器，
 * 故所有操作都在共享计数器上加锁。</p>
 */
public final class AgentBudget {

    /** 全树共享的可变状态。 */
    private static final class Shared {
        final int limit;
        int used;

        Shared(int limit) {
            this.limit = limit;
        }
    }

    private final Shared shared;

    /** 本 agent 可见的累计上限（划拨时刻已用量 + 划拨量；根预算即总上限）。 */
    private final int limit;

    /** 根预算：本次运行的总上限。 */
    public AgentBudget(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.shared = new Shared(limit);
        this.limit = limit;
    }

    private AgentBudget(Shared shared, int limit) {
        this.shared = shared;
        this.limit = limit;
    }

    /** 本 agent 的可见上限（累计口径）。 */
    public int limit() {
        return limit;
    }

    /** 全树已消耗的 token 总量。 */
    public int used() {
        synchronized (shared) {
            return shared.used;
        }
    }

    /** 本 agent 当前还能消耗多少：同时受自身划拨额度和全树总量约束。 */
    public int remaining() {
        synchronized (shared) {
            return Math.max(0, Math.min(limit, shared.limit) - shared.used);
        }
    }

    /** 记一次消耗。输入体积的预检由调用方用 {@link #remaining()} 判断。 */
    public void spend(int tokens) {
        if (tokens <= 0) {
            return;
        }
        synchronized (shared) {
            shared.used += tokens;
        }
    }

    /**
     * 划拨一份子预算给子 agent。上限钉在当前已用量加 {@code granted} 上，
     * 因此子 agent 无法看到、也无法动用到父 agent 的剩余额度。
     */
    public AgentBudget fork(int granted) {
        if (granted <= 0) {
            throw new IllegalArgumentException("granted 必须大于 0");
        }
        synchronized (shared) {
            return new AgentBudget(shared, shared.used + granted);
        }
    }

    /**
     * 父 agent 留给自己的额度：划拨时不要把剩余额度全给子 agent，
     * 否则子 agent 一旦耗尽，父 agent 就没额度做收尾了。至少留 1。
     */
    public static int reserveForParent(int total) {
        return Math.max(1, total / 4);
    }

    /** 默认划拨比例：子 agent 拿到剩余额度的 2/3，父 agent 留 1/3。 */
    public static int defaultGrant(int available) {
        return Math.max(1, available - reserveForParent(available));
    }
}
