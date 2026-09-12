package cn.thyme.bootstrap.memory;

import cn.thyme.llm.model.LlmMessage;
import cn.thyme.memory.MemoryRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史回注渲染器：把 {@link MemoryRecord} 形式的会话历史转成给模型看的
 * {@link LlmMessage} 列表。
 *
 * <p>降级策略：助手消息原样回注；工具调用则<b>不展开</b>其参数与结果，只留一行
 * 占位（带会话内序号、工具名、成败），因为工具输出往往很长，全量回注会迅速
 * 吃掉上下文。模型若需要细节，可依据占位里的 {@code #seq} 通过专门的工具
 * 回查具体调用。</p>
 *
 * <p>占位用 assistant 角色而非 tool 角色：OpenAI 协议的 tool 消息必须紧跟
 * 对应的 assistant.tool_calls，在历史里重建那一对消息就等于把内容全量展开了，
 * 与本策略相悖。</p>
 */
public class HistoryRenderer {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_TOOL_CALL = "TOOL_CALL";
    private static final String ROLE_TOOL_RESULT = "TOOL_RESULT";

    /**
     * 把按 seq 排好序的历史记录转成模型消息列表。
     *
     * @param records 同一会话的记录，已按 seq 升序
     */
    public List<LlmMessage> render(List<MemoryRecord> records) {
        List<LlmMessage> messages = new ArrayList<>();
        for (MemoryRecord record : records) {
            String role = roleOf(record);
            if (ROLE_USER.equals(role)) {
                messages.add(LlmMessage.user(record.content()));
            } else if (ROLE_TOOL_CALL.equals(role) || ROLE_TOOL_RESULT.equals(role)) {
                messages.add(LlmMessage.assistant(placeholder(record)));
            } else {
                // ASSISTANT 及未知角色一律按助手文本回注
                messages.add(LlmMessage.assistant(record.content()));
            }
        }
        return messages;
    }

    /** 单条工具调用的占位文本，形如 {@code [历史工具调用 #12] read_file → 成功（结果未展开）}。 */
    private String placeholder(MemoryRecord record) {
        return "[历史工具调用 #" + seqOf(record) + "] " + toolName(record)
                + " → " + (isFailed(record) ? "失败" : "成功") + "（结果未展开）";
    }

    /** 工具名直接读 metadata（落库时已写入），不再从 content 文本里解析。 */
    private static String toolName(MemoryRecord record) {
        Object name = record.metadata().get("toolName");
        return name == null ? "" : String.valueOf(name);
    }

    /** 结果段落里的 {@code (ok=false)} 标记；没有结果段时视为已成功完成调用。 */
    private static boolean isFailed(MemoryRecord record) {
        String content = record.content() == null ? "" : record.content();
        return content.contains("(ok=false)");
    }

    private static String roleOf(MemoryRecord record) {
        Object role = record.metadata().get("role");
        return role == null ? "" : String.valueOf(role);
    }

    private static long seqOf(MemoryRecord record) {
        Map<String, Object> metadata = record.metadata();
        Object seq = metadata.get("seq");
        return seq instanceof Number n ? n.longValue() : 0L;
    }
}
