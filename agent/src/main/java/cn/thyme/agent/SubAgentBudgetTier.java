package cn.thyme.agent;

/**
 * 子 agent 的预算档位：主 agent 委派时只表达「这个任务大概要多少」，
 * 由系统把它折算成实际划拨额度。
 *
 * <p>刻意让 LLM 传档位而非绝对数字：模型对自身剩余额度没有可靠感知，
 * 传绝对值时倾向保守（给 100000 也常填几千），反而把复杂任务饿死。
 * 档位把「有多少」的决策留在系统侧，模型只回答「任务多重」。</p>
 */
public enum SubAgentBudgetTier {

    /** 轻量：单点查询、简单转换这类几步就能收尾的子任务。 */
    SMALL(1, 4),

    /** 中等（默认）：需要多轮工具往返、自己读几个文件的子任务。 */
    MEDIUM(1, 2),

    /** 重型：需要广泛探索、大量阅读或多次试错的子任务。 */
    LARGE(2, 3);

    private final int numerator;
    private final int denominator;

    SubAgentBudgetTier(int numerator, int denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * 按本档位从「当前可用额度」折算划拨量。
     * 无论档位多大都留一部分给父 agent 收尾——否则子 agent 耗尽后父 agent 无法作答。
     */
    public int grantFrom(int available) {
        if (available <= 0) {
            return 0;
        }
        int grant = (int) ((long) available * numerator / denominator);
        // 兜底：至少留 1 给父级（available 为 1 时无法两全，让父级保留）
        int maxAllowable = Math.max(1, available - AgentBudget.reserveForParent(available));
        return Math.clamp(grant, 1, maxAllowable);
    }

    /** 解析档位名；无法识别时回落到默认档。大小写与首尾空白都容忍。 */
    public static SubAgentBudgetTier parse(String name) {
        if (name == null || name.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
