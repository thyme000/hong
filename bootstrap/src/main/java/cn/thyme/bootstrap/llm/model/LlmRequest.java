package cn.thyme.bootstrap.llm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        Double temperature,
        Integer maxTokens) {

    public LlmRequest {
        Objects.requireNonNull(model, "model must not be null");
        messages = List.copyOf(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private final List<LlmMessage> messages = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;

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

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(this.model, List.copyOf(this.messages), this.temperature, this.maxTokens);
        }
    }
}
