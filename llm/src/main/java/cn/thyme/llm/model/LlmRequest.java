package cn.thyme.llm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次 LLM 请求：模型、消息、可选的工具定义列表、温度、最大 token、深度思考档位。
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        List<LlmToolDefinition> tools,
        Double temperature,
        Integer maxTokens,
        ThinkingLevel thinking) {

    public LlmRequest {
        Objects.requireNonNull(model, "model must not be null");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<LlmToolDefinition> tools = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private ThinkingLevel thinking;

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<LlmMessage> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder addMessage(LlmMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder addSystemMessage(String content) {
            return addMessage(LlmMessage.system(content));
        }

        public Builder addUserMessage(String content) {
            return addMessage(LlmMessage.user(content));
        }

        public Builder addAssistantMessage(String content) {
            return addMessage(LlmMessage.assistant(content));
        }

        public Builder tools(List<LlmToolDefinition> tools) {
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        public Builder addTool(LlmToolDefinition tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder thinking(ThinkingLevel thinking) {
            this.thinking = thinking;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(this.model, List.copyOf(this.messages),
                    List.copyOf(this.tools), this.temperature, this.maxTokens, this.thinking);
        }
    }
}
