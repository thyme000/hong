package cn.thyme.agent;

import cn.thyme.llm.model.LlmMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 的上下文：global 为全局上下文（目标/约束，所有 agent 共享），
 * task 为本次任务描述（委派时由主 agent 传递），history 为 agent 私有的对话历史。
 * 每个 agent 持独立实例，实现上下文隔离。
 *
 * <p>会话有两个维度，委派时二者会分叉：</p>
 * <ul>
 *   <li>{@code sessionId}——<b>历史</b>归属。子 agent 用派生的子会话，
 *       这样它的消息序列与父会话隔离，不会污染父会话的上下文。</li>
 *   <li>{@code workspaceSessionId}——<b>工作区</b>归属。文件工具据此定位沙箱目录，
 *       子 agent 应沿用父会话的值，否则它看不到用户上传给父会话的文件。</li>
 * </ul>
 * 不显式设置时 workspaceSessionId 跟随 sessionId（顶层运行的常见情形）。
 */
public record AgentContext(String global, String task, List<LlmMessage> history,
                           String sessionId, String workspaceSessionId) {

    public AgentContext {
        global = global == null ? "" : global;
        history = history == null ? List.of() : List.copyOf(history);
        sessionId = sessionId == null || sessionId.isEmpty() ? UUID.randomUUID().toString() : sessionId;
        workspaceSessionId = workspaceSessionId == null || workspaceSessionId.isEmpty()
                ? sessionId : workspaceSessionId;
    }

    /** 只有会话 id 的简写：工作区随之。 */
    public AgentContext(String global, String task, List<LlmMessage> history, String sessionId) {
        this(global, task, history, sessionId, sessionId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String global;
        private String task;
        private final List<LlmMessage> history = new ArrayList<>();
        private String sessionId;
        private String workspaceSessionId;

        private Builder() {
        }

        public Builder global(String global) {
            this.global = global;
            return this;
        }

        public Builder task(String task) {
            this.task = task;
            return this;
        }

        public Builder history(List<LlmMessage> history) {
            this.history.clear();
            this.history.addAll(history);
            return this;
        }

        public Builder addMessage(LlmMessage message) {
            this.history.add(message);
            return this;
        }

        /** 历史归属的会话 id（子 agent 用派生子会话）。 */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** 工作区归属的会话 id（子 agent 沿用父会话）；不设则跟随 sessionId。 */
        public Builder workspaceSessionId(String workspaceSessionId) {
            this.workspaceSessionId = workspaceSessionId;
            return this;
        }

        public AgentContext build() {
            return new AgentContext(global, task, List.copyOf(history), sessionId, workspaceSessionId);
        }
    }
}
