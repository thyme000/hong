package cn.thyme.llm.model;

import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用：唯一 id、工具名、参数（已解析为 Map）。
 * 由厂商适配器把协议返回的 JSON 参数解析后填入。
 */
public record LlmToolCall(String id, String name, Map<String, Object> arguments) {

    public LlmToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
