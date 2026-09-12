package cn.thyme.tool.security;

/**
 * 工具执行的隔离级别。
 */
public enum IsolationLevel {
    /** 无隔离，用普通执行器。 */
    NONE,
    /** 线程隔离，用独立线程池 + 超时/资源限制。 */
    THREAD,
    /** 进程隔离（预留，暂未实现）。 */
    PROCESS
}
