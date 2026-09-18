package cn.thyme.bootstrap.memory;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.memory.MemoryQuery;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史监听器：把 agent 运行中的消息（user / assistant / tool 调用与结果）
 * 持久化到 {@link MemoryStore}，供事后完整回看与回注。
 *
 * <p>存储约定：</p>
 * <ul>
 *   <li>id 为 {@code sessionId--seq}，seq 在<b>会话内</b>递增（从 1 开始）。
 *       每次启动后首次用到某个会话时，从库中恢复该会话的 max(seq)+1，避免重号。</li>
 *   <li>metadata 记录：type=message、sessionId、role、seq、timestamp；
 *       工具调用额外带 toolCallId 与 toolName。</li>
 *   <li>一次工具调用合并成<b>一条</b>记录：TOOL_CALL 先落库占住 seq，
 *       TOOL_RESULT 到达后按 toolCallId 找回并原地补全，键沿用调用时的 seq。</li>
 *   <li>运行时缓存（seq 计数器、待配对队列）随一次运行结束释放，内存占用
 *       只与「正在运行的会话数」相关。</li>
 * </ul>
 */
@Component
public class ConversationHistoryListener implements AgentListener {

    private static final String TYPE_MESSAGE = "message";

    private final MemoryStore store;

    /** 每个会话一个递增计数器；首次用到时从库中恢复。随一次运行结束清理。 */
    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    /**
     * 每个会话一个待配对队列，按 {@code toolCallId} 精确配对（不依赖事件先后顺序）。
     * 仅在一次运行期间有意义，运行结束（{@link AgentEvent.Finished}）即清理，
     * 因此内存占用只与「正在运行的会话数」相关，不随累计会话数增长。
     */
    private final Map<String, Deque<PendingCall>> pendingToolCalls = new ConcurrentHashMap<>();

    public ConversationHistoryListener(MemoryStore store) {
        this.store = store;
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.Message e -> saveMessage(e.sessionId(), e.parentSessionId(),
                    e.role().name(), e.content(), e.timestamp());
            case AgentEvent.ToolCall e -> saveToolCall(e);
            case AgentEvent.ToolResult e -> updateToolResult(e);
            case AgentEvent.Finished e -> releaseRun(e.sessionId());
            default -> {
                // Started 落库暂不需要，后续可用于会话元信息
            }
        }
    }

    /**
     * 一次运行结束，释放该会话的运行时缓存：待配对队列（此时应已排空）与
     * seq 计数器。下次再写入该会话时按需从库中重建（见 {@link #nextSeq}）。
     */
    private void releaseRun(String sessionId) {
        counters.remove(sessionId);
        pendingToolCalls.remove(sessionId);
    }

    /** 该会话当前残留的运行时缓存条目数（0 表示已释放）；供测试观测。 */
    int runtimeStateSize(String sessionId) {
        return (counters.containsKey(sessionId) ? 1 : 0) + (pendingToolCalls.containsKey(sessionId) ? 1 : 0);
    }

    /** 某会话的全部消息，按 seq 排序。 */
    public List<MemoryRecord> history(String sessionId) {
        return store.query(MemoryQuery.builder().eq("sessionId", sessionId).build()).stream()
                .sorted((a, b) -> Long.compare(seqOf(a), seqOf(b)))
                .toList();
    }

    /**
     * 删除某会话的全部历史记录，<b>连同它派生的所有子会话历史</b>，返回删除条数。
     *
     * <p>子会话的消息挂在 {@code 主id-sub-xxxxxxxx} 上，与主会话的 id 并不相等，
     * 所以按 {@code sessionId} 精确匹配删不到它们。归属关系由落库时写下的
     * {@code parentSessionId} 承载（见 {@link AgentContext#parentSessionId()}），
     * 这里按它做第二次精确匹配——只清一层，因为子 agent 不能再派生子 agent。</p>
     */
    public int deleteSession(String sessionId) {
        int removed = store.deleteWhere(MemoryQuery.builder().eq("sessionId", sessionId).build());
        removed += store.deleteWhere(MemoryQuery.builder().eq("parentSessionId", sessionId).build());
        counters.remove(sessionId);
        pendingToolCalls.remove(sessionId);
        return removed;
    }

    private void saveMessage(String sessionId, String parentSessionId, String role,
                             String content, Instant timestamp) {
        long seq = nextSeq(sessionId);
        store.save(record(sessionId, seq, content,
                baseMetadata(sessionId, parentSessionId, role, seq, timestamp)));
    }

    /** 工具调用先落库占住 seq，等结果到达后原地补全。 */
    private void saveToolCall(AgentEvent.ToolCall e) {
        long seq = nextSeq(e.sessionId());
        Map<String, Object> metadata = baseMetadata(e.sessionId(), e.parentSessionId(),
                "TOOL_CALL", seq, e.timestamp());
        metadata.put("toolCallId", e.toolCallId());
        metadata.put("toolName", e.toolName());
        store.save(record(e.sessionId(), seq, e.toolName() + " " + e.arguments(), metadata));
        pendingToolCalls
                .computeIfAbsent(e.sessionId(), k -> new ArrayDeque<>())
                .addLast(new PendingCall(seq, e.toolCallId()));
    }

    /** 结果到达：按 toolCallId 找回对应调用记录，原地更新为「调用 + 结果」。 */
    private void updateToolResult(AgentEvent.ToolResult e) {
        Deque<PendingCall> pending = pendingToolCalls.get(e.sessionId());
        PendingCall call = pending == null ? null : removeByCallId(pending, e.toolCallId());
        if (call == null) {
            // 没有可配对的调用（进程重启、事件乱序等）：退化为独立记录，不丢数据
            long fallback = nextSeq(e.sessionId());
            Map<String, Object> metadata = baseMetadata(e.sessionId(), e.parentSessionId(),
                    "TOOL_RESULT", fallback, e.timestamp());
            metadata.put("toolCallId", e.toolCallId());
            metadata.put("toolName", e.toolName());
            store.save(record(e.sessionId(), fallback, e.toolName() + " -> " + e.result(), metadata));
            return;
        }
        MemoryRecord existing = store.get(messageId(e.sessionId(), call.seq())).orElse(null);
        if (existing == null) {
            return;
        }
        // 调用段已是「工具名 + 参数」，此处不再重复工具名
        String content = existing.content() + "\n-> (ok=" + e.ok() + ") " + e.result();
        store.save(new MemoryRecord(existing.id(), content, existing.metadata()));
    }

    /** 按调用 id 从待配对队列中摘出对应项；找不到返回 null。 */
    private static PendingCall removeByCallId(Deque<PendingCall> pending, String toolCallId) {
        for (PendingCall call : pending) {
            if (call.toolCallId().equals(toolCallId)) {
                pending.remove(call);
                return call;
            }
        }
        return null;
    }

    /** 一次待配对的工具调用：落库序号 + 调用 id。 */
    private record PendingCall(long seq, String toolCallId) {
    }

    /** 会话内递增：从 1 开始，首次用到该会话时恢复库中已有的最大值。 */
    private long nextSeq(String sessionId) {
        return counters.compute(sessionId, (id, current) -> {
            if (current != null) {
                return current + 1;
            }
            long max = history(sessionId).stream()
                    .mapToLong(ConversationHistoryListener::seqOf)
                    .max()
                    .orElse(0L);
            return max + 1;
        });
    }

    private static MemoryRecord record(String sessionId, long seq, String content, Map<String, Object> metadata) {
        return new MemoryRecord(messageId(sessionId, seq), content, metadata);
    }

    private static String messageId(String sessionId, long seq) {
        return sessionId + "--" + seq;
    }

    private static Map<String, Object> baseMetadata(String sessionId, String parentSessionId,
                                                    String role, long seq, Instant timestamp) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", TYPE_MESSAGE);
        metadata.put("sessionId", sessionId);
        // 顶层运行没有父级，此时不写该键：一条恒为 null 的字段只会占空间，
        // 还会让「按 parentSessionId 查」多出一堆无意义命中
        if (parentSessionId != null && !parentSessionId.isEmpty()) {
            metadata.put("parentSessionId", parentSessionId);
        }
        metadata.put("role", role);
        metadata.put("seq", seq);
        metadata.put("timestamp", timestamp.toString());
        return metadata;
    }

    private static long seqOf(MemoryRecord record) {
        Object seq = record.metadata().get("seq");
        return seq instanceof Number n ? n.longValue() : 0L;
    }
}
