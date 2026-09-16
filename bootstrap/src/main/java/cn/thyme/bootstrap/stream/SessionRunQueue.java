package cn.thyme.bootstrap.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 同一会话内的任务排队串行，不同会话并行。
 *
 * <p>为什么必须串行：agent 运行时会读取该会话的历史，并在结束时追加新记录。
 * 两条同会话的流同时跑，会读到彼此都不含对方结果的旧历史，并在写回时按各自的
 * seq 计数落库——上下文与历史双双错乱。跨会话没有这个约束，故仍并行。</p>
 *
 * <p>队列是<b>进程内</b>的：重启即丢，多实例部署时各自为政。单机够用，要横向扩展
 * 得换成外部队列或按会话做粘性路由。</p>
 */
public class SessionRunQueue {

    private static final Logger log = LoggerFactory.getLogger(SessionRunQueue.class);

    /** 每个会话一条任务链，存的是该会话当前队尾。 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final Executor executor;

    public SessionRunQueue(Executor executor) {
        this.executor = executor;
    }

    /**
     * 把任务排到该会话的队尾，前一个任务结束后才开始。
     *
     * @return 本次任务的句柄，调用方可据此感知完成
     */
    public CompletableFuture<Void> submit(String sessionId, Runnable task) {
        CompletableFuture<Void> previous = tails.get(sessionId);
        // 关键：前一个任务的异常必须在这里吃掉。否则异常会顺着链传下来，
        // 让此后提交到该会话的每一个任务都直接失败——一次报错永久锁死会话
        CompletableFuture<Void> ready = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.exceptionally(e -> null);

        CompletableFuture<Void> current = ready.thenRunAsync(task, executor);

        tails.put(sessionId, current);
        // 队尾若还是自己，说明期间没有新任务排进来，可以摘掉，避免 map 无限增长
        current.whenComplete((ignored, error) -> tails.remove(sessionId, current));
        return current;
    }
}
