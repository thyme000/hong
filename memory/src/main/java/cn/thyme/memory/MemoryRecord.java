package cn.thyme.memory;

import java.util.Map;
import java.util.Objects;

/**
 * 一条记忆记录：唯一 id、文本内容、任意元数据。
 * 元数据用 Map 表达，可为空。
 */
public record MemoryRecord(String id, String content, Map<String, Object> metadata) {

    public MemoryRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(content, "content must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static MemoryRecord of(String id, String content) {
        return new MemoryRecord(id, content, Map.of());
    }

    public static MemoryRecord of(String id, String content, Map<String, Object> metadata) {
        return new MemoryRecord(id, content, metadata);
    }
}
