package cn.thyme.bootstrap.llm.model;

import java.util.Objects;

public record LlmResponse(
        String model,
        String content) {

    public LlmResponse {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
