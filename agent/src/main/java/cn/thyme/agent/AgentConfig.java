package cn.thyme.agent;

import cn.thyme.llm.LlmClient;
import cn.thyme.tool.Tool;

import java.util.List;
import java.util.Objects;

/**
 * Agent 的配置：名称、模型提供商、模型名、系统提示、工具集、
 * 最大轮次与累计 token 上限。属性与执行逻辑解耦，通过 {@link #builder()} 构建。
 */
public record AgentConfig(
        String name,
        LlmClient llmClient,
        String model,
        String systemPrompt,
        List<Tool> tools,
        int maxIterations,
        int maxTokens) {

    public AgentConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(model, "model must not be null");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be > 0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private LlmClient llmClient;
        private String model;
        private String systemPrompt;
        private List<Tool> tools = List.of();
        private int maxIterations = 10;
        private int maxTokens = 100000;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(name, llmClient, model, systemPrompt,
                    tools, maxIterations, maxTokens);
        }
    }
}
