package cn.thyme.llm.model;

import java.util.Map;
import java.util.Objects;

/**
 * 工具定义：名称、说明、入参 JSON Schema（Map 形式）。
 * 请求时附在 {@link LlmRequest} 上，供 LLM 决定是否调用。
 */
public record LlmToolDefinition(String name, String description, Map<String, Object> inputSchema) {

    public LlmToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
