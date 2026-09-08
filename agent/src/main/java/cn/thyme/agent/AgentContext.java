package cn.thyme.agent;

import cn.thyme.llm.model.LlmMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 的上下文：global 为全局上下文（目标/约束，所有 agent 共享），
 * task 为本次任务描述（委派时由主 agent 传递），history 为 agent 私有的对话历史。
 * 每个 agent 持独立实例，实现上下文隔离。
 */
public record AgentContext(String global, String task, List<LlmMessage> history) {

    public AgentContext {
        global = global == null ? "" : global;
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String global;
        private String task;
        private final List<LlmMessage> history = new ArrayList<>();

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

        public AgentContext build() {
            return new AgentContext(global, task, List.copyOf(history));
        }
    }
}
