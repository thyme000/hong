package cn.thyme.agent.event;

import cn.thyme.agent.AgentResult;
import cn.thyme.llm.model.LlmRole;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 运行过程中发出的事件。Agent 只负责发事件，具体处理（存会话历史、
 * 收集日志、经验沉淀等）由 {@link AgentListener} 决定，两者解耦。
 */
public sealed interface AgentEvent {

    String sessionId();

    /**
     * 发起本次运行的会话 id；顶层运行返回 {@code null}。
     *
     * <p>委派产生的子会话需要能被追溯到它的主会话——删除主会话时要连带清掉整棵子树，
     * 靠 id 前缀猜（{@code 主id-sub-xxxxxxxx}）是把一个结构关系押在字符串格式上。
     * 这个字段是那条关系的显式载体。用包装类型而不是 {@code Optional}：
     * 每个事件都要多分配一个对象，而「没有父级」是常态。</p>
     */
    String parentSessionId();

    Instant timestamp();

    /** Agent 开始运行。 */
    record Started(String sessionId, String parentSessionId, Instant timestamp, String task)
            implements AgentEvent {
    }

    /** 一条对话消息（user / assistant 文本）。 */
    record Message(String sessionId, String parentSessionId, Instant timestamp, LlmRole role, String content)
            implements AgentEvent {
    }

    /**
     * 模型产出的一个增量片段，用于实时展示。
     *
     * <p><b>只推送，不持久化</b>：一次回答会发出几十上百条，逐条落库会污染历史
     * （回注时每片都会变成一条独立消息）。完整文本仍由轮次结束时的
     * {@link Message} 承载，那才是唯一的持久化单元——所以监听器忽略本事件是正确行为。</p>
     */
    record Delta(String sessionId, String parentSessionId, Instant timestamp, String text,
                 boolean reasoning) implements AgentEvent {
    }

    /** 调用工具。toolCallId 是 LLM 分配的调用 id，用于与结果配对。 */
    record ToolCall(String sessionId, String parentSessionId, Instant timestamp, String toolCallId,
                    String toolName, Map<String, Object> arguments) implements AgentEvent {

        public ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 工具执行结果。toolCallId 与对应的 {@link ToolCall} 一致。 */
    record ToolResult(String sessionId, String parentSessionId, Instant timestamp, String toolCallId,
                      String toolName, String result, boolean ok) implements AgentEvent {
    }

    /** Agent 运行结束。 */
    record Finished(String sessionId, String parentSessionId, Instant timestamp, AgentResult result)
            implements AgentEvent {
    }
}
