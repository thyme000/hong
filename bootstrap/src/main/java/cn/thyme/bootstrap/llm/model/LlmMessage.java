package cn.thyme.bootstrap.llm.model;

import java.util.Objects;

public record LlmMessage(LlmRole role, String content) {

    public LlmMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static LlmMessage of(LlmRole role, String content) {
        return new LlmMessage(role, content);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content);
    }
}
