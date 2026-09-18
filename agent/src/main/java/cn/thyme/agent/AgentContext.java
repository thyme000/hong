package cn.thyme.agent;

import cn.thyme.agent.event.AgentListener;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.ThinkingLevel;

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
 *
 * <p>{@code parentSessionId} 是<b>归属</b>指针：委派产生的子运行据此记下自己属于谁，
 * 删除主会话时才能连带清掉整棵子树。只在委派的子上下文里设置；顶层运行恒为 null，
 * 不在这里把「顶层」填成自身 id——那只是把一个没有父子关系的值伪装成有关系，
 * 消费方（历史落库）会因此给每条顶层消息写一个毫无意义的 parent 字段。</p>
 *
 * <p>另有两个<b>按次运行</b>而非按 agent 配置的开关：{@code listeners} 让某一次运行
 * 只发给特定监听器（流式端点据此把事件推给本次请求的连接），{@code thinking} 由调用方
 * 按轮次决定是否开启深度思考。二者都放在 context 而不是 {@link AgentConfig}——同一次
 * 部署里不同请求的取值本就不同，配置项表达不了。</p>
 */
public record AgentContext(String global, String task, List<LlmMessage> history,
                           String sessionId, String workspaceSessionId, String parentSessionId,
                           List<AgentListener> listeners, ThinkingLevel thinking) {

    public AgentContext {
        global = global == null ? "" : global;
        history = history == null ? List.of() : List.copyOf(history);
        sessionId = sessionId == null || sessionId.isEmpty() ? UUID.randomUUID().toString() : sessionId;
        workspaceSessionId = workspaceSessionId == null || workspaceSessionId.isEmpty()
                ? sessionId : workspaceSessionId;
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /** 只有会话 id 的简写：工作区随之，无父级，监听器用全局，思考关闭。 */
    public AgentContext(String global, String task, List<LlmMessage> history, String sessionId) {
        this(global, task, history, sessionId, sessionId, null, null, null);
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
        private String parentSessionId;
        private List<AgentListener> listeners;
        private ThinkingLevel thinking;

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

        /** 父会话 id（委派的子运行才设）；不设表示顶层运行。 */
        public Builder parentSessionId(String parentSessionId) {
            this.parentSessionId = parentSessionId;
            return this;
        }

        /** 本次运行专属的监听器；不设或传空则使用全局监听器。 */
        public Builder listeners(List<AgentListener> listeners) {
            this.listeners = listeners;
            return this;
        }

        /** 深度思考档位；null 表示关闭（默认）。 */
        public Builder thinking(ThinkingLevel thinking) {
            this.thinking = thinking;
            return this;
        }

        public AgentContext build() {
            return new AgentContext(global, task, List.copyOf(history), sessionId,
                    workspaceSessionId, parentSessionId, listeners, thinking);
        }
    }
}
