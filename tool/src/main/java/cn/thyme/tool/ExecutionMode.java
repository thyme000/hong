package cn.thyme.tool;

/**
 * 工具的执行模式：声明该工具只能同步、只能异步，还是两者皆可。
 */
public enum ExecutionMode {
    /** 只能在调用线程上同步执行。 */
    SYNC_ONLY,
    /** 只能异步执行，不能在调用线程上阻塞等待。 */
    ASYNC_ONLY,
    /** 同步、异步均可，由任务决定是否降级为同步。 */
    BOTH
}
