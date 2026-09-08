package cn.thyme.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 抽象：封装「接收任务 → 循环（LLM + 工具）→ 返回结果」。
 * 提供同步 {@link #run} 与异步 {@link #runAsync} 双入口。
 */
public interface Agent {

    /** 同步执行并返回结果。 */
    AgentResult run(AgentContext context);

    /** 异步执行。 */
    default CompletableFuture<AgentResult> runAsync(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> run(context));
    }
}
