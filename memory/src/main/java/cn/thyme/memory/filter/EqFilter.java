package cn.thyme.memory.filter;

import cn.thyme.memory.MemoryRecord;

import java.util.Objects;

/**
 * 精确匹配：metadata 中指定 key 的值等于给定 value。
 */
public record EqFilter(String key, Object value) implements Filter {

    public EqFilter {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }

    @Override
    public boolean matches(MemoryRecord record) {
        Object actual = record.metadata().get(key);
        return actual != null && actual.equals(value);
    }
}
