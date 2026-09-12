package cn.thyme.bootstrap.memory;

import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmRole;
import cn.thyme.memory.MemoryRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史降级回注：助手消息原样回注，工具调用压成一行占位（不展开结果），
 * 且占位用 assistant 角色。
 */
class HistoryRendererTest {

    private final HistoryRenderer renderer = new HistoryRenderer();

    private static MemoryRecord record(long seq, String role, String content) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("seq", seq);
        metadata.put("role", role);
        metadata.put("sessionId", "s-1");
        // 模拟 listener 落库：工具类记录会把工具名写进 metadata（渲染时直读）
        if ("TOOL_CALL".equals(role) || "TOOL_RESULT".equals(role)) {
            metadata.put("toolName", firstWord(content));
        }
        return new MemoryRecord("s-1--" + seq, content, metadata);
    }

    private static String firstWord(String content) {
        if (content == null) {
            return "";
        }
        int end = content.indexOf(' ');
        return end < 0 ? content : content.substring(0, end);
    }

    private static String textOf(LlmMessage message) {
        return message.content() == null ? "" : message.content();
    }

    @Test
    @DisplayName("用户与助手消息原样回注，角色正确")
    void rendersPlainMessages() {
        List<LlmMessage> messages = renderer.render(List.of(
                record(1, "USER", "你好"),
                record(2, "ASSISTANT", "你好，有什么可以帮你？")));

        assertEquals(2, messages.size());
        assertEquals(LlmRole.USER, messages.get(0).role());
        assertEquals("你好", textOf(messages.get(0)));
        assertEquals(LlmRole.ASSISTANT, messages.get(1).role());
        assertEquals("你好，有什么可以帮你？", textOf(messages.get(1)));
    }

    @Test
    @DisplayName("工具调用压成一行占位，带序号、工具名、成败，且不展开结果")
    void degradesToolCallToPlaceholder() {
        String content = "read_file {path=a.txt}\n-> (ok=true) 这是很长很长的文件正文，不应出现在占位里";

        List<LlmMessage> messages = renderer.render(List.of(record(3, "TOOL_CALL", content)));

        assertEquals(1, messages.size());
        String placeholder = textOf(messages.get(0));
        assertEquals("[历史工具调用 #3] read_file → 成功（结果未展开）", placeholder);
        assertTrue(messages.get(0).toolCalls().isEmpty(), "不应重建真实的 tool_calls 结构");
    }

    @Test
    @DisplayName("工具占位用 assistant 角色（避免 tool 消息必须紧跟 tool_calls 的约束）")
    void placeholderUsesAssistantRole() {
        List<LlmMessage> messages = renderer.render(List.of(record(4, "TOOL_CALL", "list_files -> (ok=true) []")));

        assertEquals(LlmRole.ASSISTANT, messages.get(0).role());
    }

    @Test
    @DisplayName("失败的工具调用在占位里显示为失败")
    void marksFailedToolCall() {
        List<LlmMessage> messages = renderer.render(
                List.of(record(5, "TOOL_CALL", "read_file {path=x.txt}\n-> (ok=false) 文件不存在")));

        assertEquals("[历史工具调用 #5] read_file → 失败（结果未展开）", textOf(messages.get(0)));
    }

    @Test
    @DisplayName("工具名从内容首词提取，兼容带参数的形式")
    void extractsToolName() {
        List<LlmMessage> messages = renderer.render(
                List.of(record(6, "TOOL_CALL", "list_files {} -> (ok=true) [{\"name\":\"a.txt\"}]")));

        assertTrue(textOf(messages.get(0)).contains("list_files"));
    }

    @Test
    @DisplayName("没有结果段的调用（仅占位）也视为成功")
    void placeholderWithoutResultIsSuccess() {
        List<LlmMessage> messages = renderer.render(List.of(record(7, "TOOL_CALL", "read_file {path=a.txt}")));

        assertEquals("[历史工具调用 #7] read_file → 成功（结果未展开）", textOf(messages.get(0)));
    }

    @Test
    @DisplayName("完整一轮对话按序回注，工具调用夹在中间")
    void rendersFullTurnInOrder() {
        List<LlmMessage> messages = renderer.render(List.of(
                record(1, "USER", "帮我读 a.txt"),
                record(2, "ASSISTANT", "好的"),
                record(3, "TOOL_CALL", "read_file {path=a.txt}\n-> (ok=true) 内容"),
                record(4, "ASSISTANT", "文件里写的是……")));

        assertEquals(4, messages.size());
        assertEquals(LlmRole.USER, messages.get(0).role());
        assertEquals(LlmRole.ASSISTANT, messages.get(1).role());
        assertEquals(LlmRole.ASSISTANT, messages.get(2).role());
        assertTrue(textOf(messages.get(2)).startsWith("[历史工具调用 #3]"));
        assertEquals(LlmRole.ASSISTANT, messages.get(3).role());
        assertEquals("文件里写的是……", textOf(messages.get(3)));
    }

    @Test
    @DisplayName("空历史返回空列表")
    void rendersEmptyHistory() {
        assertTrue(renderer.render(List.of()).isEmpty());
    }
}
