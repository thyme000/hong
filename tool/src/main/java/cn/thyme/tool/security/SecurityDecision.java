package cn.thyme.tool.security;

import java.util.Objects;

/**
 * 安全决策结果：动作 + 隔离级别（两个正交维度）。
 */
public record SecurityDecision(SecurityAction action, IsolationLevel isolation) {

    public SecurityDecision {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(isolation, "isolation must not be null");
    }

    public static SecurityDecision allow(IsolationLevel isolation) {
        return new SecurityDecision(SecurityAction.ALLOW, isolation);
    }

    public static SecurityDecision confirm(IsolationLevel isolation) {
        return new SecurityDecision(SecurityAction.CONFIRM, isolation);
    }

    public static SecurityDecision deny() {
        return new SecurityDecision(SecurityAction.DENY, IsolationLevel.NONE);
    }
}
