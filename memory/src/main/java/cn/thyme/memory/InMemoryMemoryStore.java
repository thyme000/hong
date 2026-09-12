package cn.thyme.memory;

import cn.thyme.memory.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现：线程安全，适合测试与默认场景。查询结果按 id 排序以保证分页稳定。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record) {
        records.put(record.id(), record);
    }

    @Override
    public Optional<MemoryRecord> get(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public boolean delete(String id) {
        return records.remove(id) != null;
    }

    @Override
    public int deleteWhere(MemoryQuery query) {
        MemoryStore.requireFilters(query);
        int removed = 0;
        for (MemoryRecord record : List.copyOf(records.values())) {
            if (matchesAll(record, query.filters()) && records.remove(record.id()) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public List<MemoryRecord> query(MemoryQuery query) {
        List<MemoryRecord> matched = new ArrayList<>();
        for (MemoryRecord record : sorted()) {
            if (matchesAll(record, query.filters())) {
                matched.add(record);
            }
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(matched.size(), from + query.limit());
        return List.copyOf(matched.subList(from, to));
    }

    @Override
    public List<MemoryRecord> list() {
        return List.copyOf(sorted());
    }

    private List<MemoryRecord> sorted() {
        return records.values().stream()
                .sorted(java.util.Comparator.comparing(MemoryRecord::id))
                .toList();
    }

    private static boolean matchesAll(MemoryRecord record, List<Filter> filters) {
        for (Filter filter : filters) {
            if (!filter.matches(record)) {
                return false;
            }
        }
        return true;
    }
}
