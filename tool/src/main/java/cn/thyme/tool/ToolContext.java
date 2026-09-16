package cn.thyme.tool;

/**
 * 工具执行时的调用上下文：由调用方（agent / 门面）在每次执行时构造，
 * 承载本次调用的环境信息。工具据此定位自己的作用范围——例如文件工具
 * 用它拿到会话标识，从而读写该会话的沙箱目录。
 *
 * <p>字段都是不透明的字符串 / 对象引用：tool 模块不关心会话或预算本身
 * 是什么，只负责原样透传给需要它们的工具实现。</p>
 *
 * @param sessionId 会话标识（沙箱目录等隔离维度的键），可为 null
 * @param budget    本次运行的 token 预算；委派类工具据它划拨子预算。可为 null
 *                  （脱离 agent 直接调用工具时没有预算概念）
 */
public record ToolContext(String sessionId, Object budget) {

    /** 只有会话、没有预算（脱离 agent 直接调用工具时的常用形式）。 */
    public ToolContext(String sessionId) {
        this(sessionId, null);
    }

    /** 无会话、无预算（例如脱离 agent 的直接调用）。 */
    public static ToolContext none() {
        return new ToolContext(null, null);
    }

    public boolean hasSession() {
        return sessionId != null && !sessionId.isEmpty();
    }

    public boolean hasBudget() {
        return budget != null;
    }
}
