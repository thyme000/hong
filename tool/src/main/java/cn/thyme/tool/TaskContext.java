package cn.thyme.tool;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 任务对工具的需求：所属域、作用、必需的标签、数量上限与超时。
 * 用于确定性选择；把需求留空表示不限。工具描述本身仍可单独喂给 LLM 做
 * function calling，由调用方自行拼装。
 */
public record TaskContext(
        String category,
        String action,
        Set<String> requiredTags,
        int maxTools,
        Duration timeout,
        boolean preferAsync) {

    public TaskContext {
        Objects.requireNonNull(requiredTags, "requiredTags must not be null");
        requiredTags = Set.copyOf(requiredTags);
        if (maxTools < 0) {
            throw new IllegalArgumentException("maxTools must be >= 0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String category;
        private String action;
        private Set<String> requiredTags = Set.of();
        private int maxTools = Integer.MAX_VALUE;
        private Duration timeout;
        private boolean preferAsync = false;

        private Builder() {
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder requiredTags(Set<String> requiredTags) {
            this.requiredTags = requiredTags;
            return this;
        }

        public Builder maxTools(int maxTools) {
            this.maxTools = maxTools;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder preferAsync(boolean preferAsync) {
            this.preferAsync = preferAsync;
            return this;
        }

        public TaskContext build() {
            return new TaskContext(category, action, requiredTags, maxTools, timeout, preferAsync);
        }
    }
}
