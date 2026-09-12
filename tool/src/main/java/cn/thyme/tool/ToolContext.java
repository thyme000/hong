package cn.thyme.tool;

/**
 * 工具执行时的调用上下文：由调用方（agent / 门面）在每次执行时构造，
 * 承载本次调用的环境信息。工具据此定位自己的作用范围——例如文件工具
 * 用它拿到会话标识，从而读写该会话的沙箱目录。
 *
 * <p>当前只承载 sessionId。它是一个不透明的字符串标识，tool 模块不关心
 * 会话本身是什么，只把它当作隔离维度的键。</p>
 */
public record ToolContext(String sessionId) {

    /** 无会话上下文（例如脱离 agent 的直接调用）。 */
    public static ToolContext none() {
        return new ToolContext(null);
    }

    public boolean hasSession() {
        return sessionId != null && !sessionId.isEmpty();
    }
}
