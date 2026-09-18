package cn.thyme.bootstrap.stream;

import cn.thyme.agent.AgentCancelledException;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次流式运行的载体：既是 {@link AgentListener}（把事件写成 SSE 帧），
 * 也是取消入口。
 *
 * <p><b>发送必须串行化</b>：{@link SseEmitter} 不是线程安全的发送器，而心跳线程
 * 与 agent 运行线程会同时向同一个 emitter 写。所有发送统一在 {@link #lock} 内完成。</p>
 *
 * <p><b>取消</b>是两段式的：{@link #cancel()} 先发一帧带 {@code CANCELLED} 的收尾，
 * 让前端立刻知道停了，不必等下一个增量；真正让 agent 停下来的是下一次
 * {@link #onEvent} 时抛出的 {@link AgentCancelledException}。</p>
 */
public class AgentRun implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(AgentRun.class);

    /** SSE 事件名：前端按名分发，不解析 data 里的类型字段。 */
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_REASONING = "reasoning";
    private static final String EVENT_TOOL_CALL = "tool_call";
    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String EVENT_FINISHED = "finished";
    private static final String EVENT_ERROR = "error";

    private final String sessionId;
    private final SseEmitter emitter;
    private final Object lock = new Object();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** emitter 已结束（正常收尾、出错或被取消）：此后不再尝试发送。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 结束时的清理钩子（从活跃表摘除），由注册方设置。 */
    private volatile Runnable onClose = () -> {
    };

    public AgentRun(String sessionId, SseEmitter emitter) {
        this.sessionId = sessionId;
        this.emitter = emitter;
        // 三个回调都是「生命周期」回调，不是收数据的回调——数据靠 send() 推。
        // 客户端断开时它们会被触发，此时必须让 agent 停下来，否则后台空转烧 token
        emitter.onCompletion(() -> close(null));
        emitter.onError(e -> close(e));
        emitter.onTimeout(() -> close(null));
    }

    /** 设置结束时的清理动作；注册方用它把本运行从活跃表摘除。 */
    public void onClose(Runnable action) {
        this.onClose = action == null ? () -> {
        } : action;
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void onEvent(AgentEvent event) {
        // Finished 是「运行已经决定停下」的收尾通知，不是运行中的事件。在它上面抛
        // 异常拦不住任何事（要停的早就停了），只会让已结束的运行再抛一次——而这次
        // 抛出发生在 DefaultAgent 的 catch 块内，会直接穿过它「不外抛」的契约。
        boolean finished = event instanceof AgentEvent.Finished;
        if (!finished && closed.get()) {
            // 连接已经断了或已收尾：继续跑下去只是空烧 token
            throw new AgentCancelledException("流已关闭");
        }
        // 取消必须在每次事件到达时复查：这就是「立即中断」的落点
        if (!finished && cancelled.get()) {
            throw new AgentCancelledException("运行已取消");
        }
        switch (event) {
            case AgentEvent.Delta e -> send(e.reasoning() ? EVENT_REASONING : EVENT_DELTA,
                    Map.of("text", e.text()));
            case AgentEvent.ToolCall e -> send(EVENT_TOOL_CALL,
                    Map.of("toolCallId", e.toolCallId(), "toolName", e.toolName(),
                            "arguments", e.arguments()));
            case AgentEvent.ToolResult e -> send(EVENT_TOOL_RESULT,
                    Map.of("toolCallId", e.toolCallId(), "toolName", e.toolName(),
                            "ok", e.ok(), "result", e.result()));
            case AgentEvent.Finished e -> {
                send(EVENT_FINISHED, finishedPayload(e.result()));
                complete();
            }
            // Started 不单独发帧；Message 是终稿，已由 finished 帧的结果承载，
            // 再发一遍会让前端收到两遍同样的文本
            default -> {
            }
        }
    }

    /**
     * 主动取消：先给前端一帧收尾，再等下一次事件把 agent 打断。
     *
     * <p>收尾帧走与正常结束同一条路径（{@link #onEvent} 的 Finished 分支），因此
     * 三种终态——正常结束、被取消、出错回退——发出的 {@code finished} 帧结构完全
     * 一致，只有 {@code stopReason} 不同。前端不必给某个分支单独写兜底。</p>
     *
     * @return 本次调用是否真的取消了（已经结束的运行返回 false）
     */
    public boolean cancel() {
        if (closed.get()) {
            return false;
        }
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        // 立刻收尾，否则前端要等下一个增量才知道停了；而这个增量可能很久不来。
        // 此刻该运行跑到第几轮、花了多少 token 只有 agent 自己知道，这里给不出来，
        // 所以轮次与用量都是 0——它们本来就只是展示用的参考值。
        // parentSessionId 给 null：这是发给本连接的合成帧（只经本对象处理，不落库），
        // 归属指针在这里没有任何消费方
        onEvent(new AgentEvent.Finished(sessionId, null, Instant.now(),
                AgentResult.truncated(0, 0, AgentStopReason.CANCELLED, sessionId)));
        return true;
    }

    /** 发送失败时的收尾：让前端至少知道出了什么问题，而不是连接被无声掐断。 */
    public void fail(Throwable error) {
        send(EVENT_ERROR, Map.of("message", rootMessage(error)));
        complete();
    }

    /** 心跳注释帧：按 SSE 规范会被客户端忽略，仅用于避免中间代理掐断空闲连接。 */
    public void heartbeat() {
        if (closed.get()) {
            return;
        }
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // 写不出去说明连接已不可用：标记结束，让 onEvent 把 agent 打断
                log.debug("会话 {} 心跳发送失败，判定连接已断开: {}", sessionId, e.getMessage());
                closed.set(true);
            }
        }
    }

    private static Map<String, Object> finishedPayload(AgentResult result) {
        // content 在被中断时为 null，用 Map.of 会 NPE，故必须用可空值的容器
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("content", result.content());
        payload.put("stopReason", result.stopReason().name());
        payload.put("iterations", result.iterations());
        payload.put("totalTokens", result.totalTokens());
        payload.put("sessionId", result.sessionId());
        return payload;
    }

    private void send(String name, Map<String, Object> payload) {
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开（刷新页面、关标签）是常态而非故障，记 debug 即可。
                // 标记关闭后，onEvent 会在下一个事件处把 agent 打断，不空转烧 token
                log.debug("会话 {} 发送 {} 帧失败，判定连接已断开: {}", sessionId, name, e.getMessage());
                closed.set(true);
            }
        }
    }

    private void complete() {
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("会话 {} 结束流失败: {}", sessionId, e.getMessage());
            }
        }
        // 无论哪条路径结束，都要把自己从活跃表摘掉，否则取消端点会一直指着一个
        // 已经结束的运行，心跳也会一直发给它
        runOnClose();
    }

    /**
     * 走「关闭」路径：不调用 emitter 的收尾方法（连接已经没了或正在被 Spring 收尾），
     * 只做清理。断开连接时 agent 会因 {@link #closed} 在下一个事件处被打断。
     */
    private void close(Throwable error) {
        if (error != null) {
            log.debug("会话 {} 的流式连接出错: {}", sessionId, error.getMessage());
        }
        closed.set(true);
        cancelled.set(true);
        runOnClose();
    }

    private void runOnClose() {
        try {
            onClose.run();
        } catch (RuntimeException e) {
            log.debug("会话 {} 收尾清理失败: {}", sessionId, e.getMessage());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
