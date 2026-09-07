package cn.thyme.memory;

import cn.thyme.memory.filter.EqFilter;
import cn.thyme.memory.filter.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆查询：一组筛选条件（AND 关系）+ 分页（offset 从 0 开始、limit 为条数）。
 * 当前仅支持精确匹配，通过 {@link #filter(Filter)} 预留扩展。
 */
public record MemoryQuery(List<Filter> filters, int offset, int limit) {

    public MemoryQuery {
        filters = List.copyOf(filters);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Filter> filters = new ArrayList<>();
        private int offset = 0;
        private int limit = Integer.MAX_VALUE;

        private Builder() {
        }

        /** 追加一个通用筛选条件。 */
        public Builder filter(Filter filter) {
            this.filters.add(filter);
            return this;
        }

        /** 追加一个精确匹配条件：metadata 的 key 等于 value。 */
        public Builder eq(String key, Object value) {
            return filter(new EqFilter(key, value));
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public MemoryQuery build() {
            return new MemoryQuery(List.copyOf(this.filters), this.offset, this.limit);
        }
    }
}
