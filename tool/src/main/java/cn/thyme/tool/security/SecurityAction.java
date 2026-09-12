package cn.thyme.tool.security;

/**
 * 安全策略对工具调用的动作决定。
 */
public enum SecurityAction {
    /** 允许直接执行。 */
    ALLOW,
    /** 需要用户确认后执行。 */
    CONFIRM,
    /** 拒绝执行。 */
    DENY
}
