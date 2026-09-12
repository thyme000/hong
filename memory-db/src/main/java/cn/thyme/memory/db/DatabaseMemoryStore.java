package cn.thyme.memory.db;

import cn.thyme.memory.MemoryQuery;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.MemoryStore;
import cn.thyme.memory.filter.EqFilter;
import cn.thyme.memory.filter.Filter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆存储的数据库实现：基于 MyBatis，metadata 以 JSON 列存储，
 * 查询用各库的 JSON 函数在 SQL 层做精确匹配。
 * 由装配层在 memory.store.type 非 inmemory 时创建。
 */
public class DatabaseMemoryStore implements MemoryStore {

    private static final String SAFE_KEY = "[A-Za-z_][A-Za-z0-9_]*";

    private final MemoryMapper mapper;
    private final ObjectMapper json;

    public DatabaseMemoryStore(MemoryMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void save(MemoryRecord record) {
        mapper.upsert(record.id(), record.content(), toJson(record.metadata()));
    }

    @Override
    public Optional<MemoryRecord> get(String id) {
        MemoryRow row = mapper.findById(id);
        return row == null ? Optional.empty() : Optional.of(toRecord(row));
    }

    @Override
    public boolean delete(String id) {
        return mapper.deleteById(id) > 0;
    }

    @Override
    public int deleteWhere(MemoryQuery query) {
        MemoryStore.requireFilters(query);
        return mapper.deleteWhere(query.filters().stream().map(this::toFilterParam).toList());
    }

    @Override
    public List<MemoryRecord> query(MemoryQuery query) {
        List<FilterParam> filters = query.filters().stream()
                .map(this::toFilterParam)
                .toList();
        return mapper.query(filters, query.offset(), query.limit()).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<MemoryRecord> list() {
        return mapper.listAll().stream().map(this::toRecord).toList();
    }

    private FilterParam toFilterParam(Filter filter) {
        if (filter instanceof EqFilter eq) {
            String key = eq.key();
            if (!key.matches(SAFE_KEY)) {
                throw new IllegalArgumentException("非法的 metadata 键名: " + key);
            }
            return new FilterParam(key, String.valueOf(eq.value()));
        }
        throw new IllegalArgumentException("不支持的筛选类型: " + filter.getClass().getSimpleName());
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return json.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败", e);
        }
    }

    private MemoryRecord toRecord(MemoryRow row) {
        return new MemoryRecord(row.getId(), row.getContent(), fromJson(row.getMetadata()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String text) {
        if (text == null || text.isEmpty()) {
            return Map.of();
        }
        try {
            return (Map<String, Object>) json.readValue(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 反序列化失败", e);
        }
    }
}
