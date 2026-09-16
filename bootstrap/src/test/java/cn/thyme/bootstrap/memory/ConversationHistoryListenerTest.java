package cn.thyme.bootstrap.memory;

import cn.thyme.agent.AgentResult;
import cn.thyme.agent.event.AgentEvent;
import cn.thyme.llm.model.LlmRole;
import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.MemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话历史的落库行为：seq 在会话内从 1 递增（本次修复的 bug）、
 * 工具调用与结果合并成一条、按调用顺序正确配对、删除会话。
 */
class ConversationHistoryListenerTest {

    private MemoryStore store() {
        return new InMemoryMemoryStore();
    }

    private static void message(ConversationHistoryListener listener, String sessionId, LlmRole role, String text) {
        listener.onEvent(new AgentEvent.Message(sessionId, Instant.now(), role, text));
    }

    private static void toolCall(ConversationHistoryListener listener, String sessionId,
                                 String callId, String toolName) {
        listener.onEvent(new AgentEvent.ToolCall(
                sessionId, Instant.now(), callId, toolName, Map.of("path", "a.txt")));
    }

    private static void toolResult(ConversationHistoryListener listener, String sessionId,
                                   String callId, String toolName, String text, boolean ok) {
        listener.onEvent(new AgentEvent.ToolResult(
                sessionId, Instant.now(), callId, toolName, text, ok));
    }

    private static List<Long> seqs(List<MemoryRecord> records) {
        return records.stream()
                .map(r -> ((Number) r.metadata().get("seq")).longValue())
                .toList();
    }

    @Test
    @DisplayName("seq 在会话内从 1 递增，而不是全局递增")
    void seqIsPerSession() {
        MemoryStore store = store();
        ConversationHistoryListener listener = new ConversationHistoryListener(store);

        message(listener, "s-1", LlmRole.USER, "第一个问题");
        message(listener, "s-1", LlmRole.ASSISTANT, "第一个回答");
        message(listener, "s-2", LlmRole.USER, "另一个会话");

        assertEquals(List.of(1L, 2L), seqs(listener.history("s-1")));
        assertEquals(List.of(1L), seqs(listener.history("s-2")), "新会话应从 1 重新开始");
    }

    @Test
    @DisplayName("同一会话多轮对话 seq 持续递增")
    void seqGrowsAcrossTurns() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "第一轮");
        message(listener, "s-1", LlmRole.ASSISTANT, "回复一");
        message(listener, "s-1", LlmRole.USER, "第二轮");
        message(listener, "s-1", LlmRole.ASSISTANT, "回复二");

        assertEquals(List.of(1L, 2L, 3L, 4L), seqs(listener.history("s-1")));
    }

    @Test
    @DisplayName("工具调用与结果合并成一条，键沿用调用时的 seq")
    void mergesCallAndResultIntoOneRecord() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "读一下文件");
        toolCall(listener, "s-1", "call-1", "read_file");
        toolResult(listener, "s-1", "call-1", "read_file", "文件内容", true);

        List<MemoryRecord> records = listener.history("s-1");

        assertEquals(2, records.size(), "调用与结果应合并，只剩 1 条工具记录");
        assertEquals(List.of(1L, 2L), seqs(records));
        MemoryRecord toolRecord = records.get(1);
        assertTrue(toolRecord.content().contains("read_file"));
        assertTrue(toolRecord.content().contains("文件内容"), "结果应被写入同一条记录");
        assertTrue(toolRecord.content().contains("ok=true"));
        assertEquals("TOOL_CALL", toolRecord.metadata().get("role"));
        assertEquals("call-1", toolRecord.metadata().get("toolCallId"));
    }

    @Test
    @DisplayName("并发多次调用时按顺序正确配对（CALL1,CALL2,RESULT1,RESULT2）")
    void pairsConcurrentCallsByOrder() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "同时读两个文件");
        toolCall(listener, "s-1", "call-1", "read_file");
        toolCall(listener, "s-1", "call-2", "list_files");
        toolResult(listener, "s-1", "call-1", "read_file", "内容一", true);
        toolResult(listener, "s-1", "call-2", "list_files", "内容二", true);

        List<MemoryRecord> records = listener.history("s-1");
        assertEquals(3, records.size(), "两次调用应各合并成一条");

        MemoryRecord first = records.get(1);
        MemoryRecord second = records.get(2);
        assertEquals("call-1", first.metadata().get("toolCallId"));
        assertTrue(first.content().contains("内容一"), "call-1 应配到内容一");
        assertEquals("call-2", second.metadata().get("toolCallId"));
        assertTrue(second.content().contains("内容二"), "call-2 应配到内容二");
    }

    @Test
    @DisplayName("失败的工具调用被标记为 ok=false")
    void recordsFailure() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        toolCall(listener, "s-1", "call-1", "read_file");
        toolResult(listener, "s-1", "call-1", "read_file", "文件不存在", false);

        MemoryRecord record = listener.history("s-1").get(0);
        assertTrue(record.content().contains("ok=false"));
        assertTrue(record.content().contains("文件不存在"));
    }

    @Test
    @DisplayName("配对失败的结果退化为独立记录，不丢数据")
    void orphanResultIsStillStored() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        toolResult(listener, "s-1", "call-x", "read_file", "孤儿结果", true);

        List<MemoryRecord> records = listener.history("s-1");
        assertEquals(1, records.size());
        assertEquals("TOOL_RESULT", records.get(0).metadata().get("role"));
        assertTrue(records.get(0).content().contains("孤儿结果"));
    }

    @Test
    @DisplayName("重启后 seq 从库中最大值继续，不重号")
    void resumesSeqAfterRestart() {
        MemoryStore store = store();
        ConversationHistoryListener listener = new ConversationHistoryListener(store);
        message(listener, "s-1", LlmRole.USER, "第一轮");
        message(listener, "s-1", LlmRole.ASSISTANT, "回复一");

        // 新实例模拟进程重启：内存计数器为空，只能从库里恢复
        ConversationHistoryListener afterRestart = new ConversationHistoryListener(store);
        message(afterRestart, "s-1", LlmRole.USER, "重启后的新消息");

        assertEquals(List.of(1L, 2L, 3L), seqs(afterRestart.history("s-1")));
    }

    @Test
    @DisplayName("会话 id 按序返回且去重")
    void listsSessions() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-2", LlmRole.USER, "b");
        message(listener, "s-1", LlmRole.USER, "a");
        message(listener, "s-1", LlmRole.ASSISTANT, "a2");

        assertEquals(List.of("s-1", "s-2"), listener.sessions());
    }

    @Test
    @DisplayName("结果乱序到达时仍按 toolCallId 精确配对")
    void pairsByCallIdEvenWhenOutOfOrder() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        toolCall(listener, "s-1", "call-1", "read_file");
        toolCall(listener, "s-1", "call-2", "list_files");
        // 结果逆序到达：先 call-2 后 call-1
        toolResult(listener, "s-1", "call-2", "list_files", "清单", true);
        toolResult(listener, "s-1", "call-1", "read_file", "正文", true);

        List<MemoryRecord> records = listener.history("s-1");
        assertEquals(2, records.size());

        MemoryRecord first = records.get(0);
        MemoryRecord second = records.get(1);
        assertEquals("call-1", first.metadata().get("toolCallId"));
        assertTrue(first.content().contains("正文"), "call-1 应配到自己的结果，而非先到的清单");
        assertEquals("call-2", second.metadata().get("toolCallId"));
        assertTrue(second.content().contains("清单"), "call-2 应配到自己的结果");
    }

    @Test
    @DisplayName("工具名写入 metadata，供渲染层直读")
    void storesToolNameInMetadata() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        toolCall(listener, "s-1", "call-1", "read_file");

        assertEquals("read_file", listener.history("s-1").get(0).metadata().get("toolName"));
    }

    @Test
    @DisplayName("一次运行结束后释放运行时缓存，内存不随累计会话数增长")
    void releasesRuntimeStateAfterRun() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "问题");
        toolCall(listener, "s-1", "call-1", "read_file");
        toolResult(listener, "s-1", "call-1", "read_file", "内容", true);
        listener.onEvent(new AgentEvent.Finished(
                "s-1", Instant.now(), AgentResult.completed("done", 1, 0, "s-1")));

        assertEquals(0, listener.runtimeStateSize("s-1"), "Finished 之后不应再保留该会话的运行时缓存");

        // 释放后继续写入，seq 仍能从库中恢复，不重号
        message(listener, "s-1", LlmRole.ASSISTANT, "继续");
        assertEquals(List.of(1L, 2L, 3L), seqs(listener.history("s-1")));
    }

    @Test
    @DisplayName("删除会话清空该会话记录，且不影响其它会话")
    void deletesSession() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "要删的");
        message(listener, "s-2", LlmRole.USER, "要留的");

        int removed = listener.deleteSession("s-1");

        assertEquals(1, removed);
        assertTrue(listener.history("s-1").isEmpty());
        assertEquals(1, listener.history("s-2").size());
    }

    @Test
    @DisplayName("删除会话后 seq 重置，重建同名会话不冲突")
    void seqResetsAfterSessionDelete() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store());

        message(listener, "s-1", LlmRole.USER, "旧的");
        listener.deleteSession("s-1");
        message(listener, "s-1", LlmRole.USER, "新的");

        List<MemoryRecord> records = listener.history("s-1");
        assertEquals(1, records.size());
        assertEquals(1L, ((Number) records.get(0).metadata().get("seq")).longValue());
    }
}
