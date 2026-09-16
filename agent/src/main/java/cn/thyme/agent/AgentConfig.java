package cn.thyme.agent;

import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.llm.LlmClient;
import cn.thyme.tool.Tool;

import java.util.List;
import java.util.Objects;

/**
 * Agent 的配置：名称、模型提供商、模型名、系统提示模板、工具集、
 * 最大轮次与累计 token 上限。属性与执行逻辑解耦，通过 {@link #builder()} 构建。
 */
public record AgentConfig(
        String name,
        LlmClient llmClient,
        String model,
        PromptTemplate systemPrompt,
        List<Tool> tools,
        int maxIterations,
        int maxTokens,
        Double temperature,
        AgentEventPublisher eventPublisher,
        AgentBudget budget) {

    public AgentConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(model, "model must not be null");
        systemPrompt = systemPrompt == null ? PromptTemplate.inline("") : systemPrompt;
        tools = tools == null ? List.of() : List.copyOf(tools);
        eventPublisher = eventPublisher == null ? new AgentEventPublisher() : eventPublisher;
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
        private PromptTemplate systemPrompt;
        private List<Tool> tools = List.of();
        private int maxIterations = 10;
        private int maxTokens = 100000;
        private Double temperature;
        private AgentEventPublisher eventPublisher;
        private AgentBudget budget;

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

        public Builder systemPrompt(PromptTemplate systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 内联模板字符串。 */
        public Builder systemPrompt(String inline) {
            return systemPrompt(PromptTemplate.inline(inline));
        }

        /** 从外部文件加载模板。 */
        public Builder systemPromptFile(String path) {
            return systemPrompt(PromptTemplate.fromFile(path));
        }

        /** 从 classpath 加载模板。 */
        public Builder systemPromptClasspath(String path) {
            return systemPrompt(PromptTemplate.fromClasspath(path));
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

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder eventPublisher(AgentEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        /** 父级划拨的预算；不设则由 {@link DefaultAgent} 按 maxTokens 自建。 */
        public Builder budget(AgentBudget budget) {
            this.budget = budget;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(name, llmClient, model, systemPrompt,
                    tools, maxIterations, maxTokens, temperature, eventPublisher, budget);
        }
    }
}
