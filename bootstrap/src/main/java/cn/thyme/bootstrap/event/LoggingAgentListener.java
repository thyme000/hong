package cn.thyme.bootstrap.event;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志监听器：把 agent 运行中的事件写入日志，便于观察运行过程。
 * 与其他日志走同一条管道（控制台 + 文件）。
 */
@Component
public class LoggingAgentListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentListener.class);

    @Override
    public void onEvent(AgentEvent event) {
        String sid = shortId(event.sessionId());
        switch (event) {
            case AgentEvent.Started e ->
                    log.info("[{}] START task={}", sid, nullSafe(e.task()));
            case AgentEvent.Message e ->
                    log.info("[{}] {}: {}", sid, e.role(), nullSafe(e.content()));
            case AgentEvent.ToolCall e ->
                    log.info("[{}] TOOL_CALL {} {}", sid, e.toolName(), e.arguments());
            case AgentEvent.ToolResult e ->
                    log.info("[{}] TOOL_RESULT {} {} -> {}", sid, e.ok() ? "OK" : "FAIL",
                            e.toolName(), nullSafe(e.result()));
            case AgentEvent.Finished e ->
                    log.info("[{}] FINISHED iterations={} tokens={}{}", sid, e.result().iterations(),
                            e.result().totalTokens(), e.result().truncated() ? " (truncated)" : "");
        }
    }

    private static String shortId(String sessionId) {
        return sessionId == null ? "-" : sessionId.substring(0, Math.min(8, sessionId.length()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
