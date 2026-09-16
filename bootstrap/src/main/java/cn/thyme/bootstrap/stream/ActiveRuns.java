package cn.thyme.bootstrap.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 活跃流式运行的登记表，兼心跳。
 *
 * <p>登记是为了让取消端点能按会话找到正在跑的那次运行；心跳是为了让中间代理
 * （nginx、公司网关）不把长时间没有数据的连接当成死连接掐掉。</p>
 */
public class ActiveRuns {

    private static final Logger log = LoggerFactory.getLogger(ActiveRuns.class);

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public ActiveRuns(Duration interval) {
        long millis = interval.toMillis();
        heartbeat.scheduleWithFixedDelay(this::beat, millis, millis, TimeUnit.MILLISECONDS);
    }

    public void register(AgentRun run) {
        AgentRun previous = runs.put(run.sessionId(), run);
        if (previous != null) {
            // 同会话串行执行，理论上不该并存两次运行；真出现了说明排队失效，
            // 记一笔总比静默覆盖好排查
            log.warn("会话 {} 已有进行中的流式运行，新的运行将其替换", run.sessionId());
        }
    }

    public void unregister(AgentRun run) {
        runs.remove(run.sessionId(), run);
    }

    /** 取消某会话正在进行的运行；没有则返回 false。 */
    public boolean cancel(String sessionId) {
        AgentRun run = runs.get(sessionId);
        return run != null && run.cancel();
    }

    /** 该会话当前是否有进行中的运行。 */
    public boolean isRunning(String sessionId) {
        return runs.containsKey(sessionId);
    }

    /** 供测试观测：当前登记的运行数。 */
    int size() {
        return runs.size();
    }

    private void beat() {
        for (AgentRun run : runs.values()) {
            try {
                run.heartbeat();
            } catch (RuntimeException e) {
                // 心跳线程不能被单个连接的问题带垮，否则所有会话一起失去保活
                log.debug("会话 {} 心跳异常: {}", run.sessionId(), e.getMessage());
            }
        }
    }

    public void shutdown() {
        heartbeat.shutdownNow();
    }
}
