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

    Instant timestamp();

    /** Agent 开始运行。 */
    record Started(String sessionId, Instant timestamp, String task) implements AgentEvent {
    }

    /** 一条对话消息（user / assistant 文本）。 */
    record Message(String sessionId, Instant timestamp, LlmRole role, String content) implements AgentEvent {
    }

    /** 调用工具。toolCallId 是 LLM 分配的调用 id，用于与结果配对。 */
    record ToolCall(String sessionId, Instant timestamp, String toolCallId, String toolName,
                    Map<String, Object> arguments) implements AgentEvent {

        public ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 工具执行结果。toolCallId 与对应的 {@link ToolCall} 一致。 */
    record ToolResult(String sessionId, Instant timestamp, String toolCallId, String toolName,
                      String result, boolean ok) implements AgentEvent {
    }

    /** Agent 运行结束。 */
    record Finished(String sessionId, Instant timestamp, AgentResult result) implements AgentEvent {
    }
}
