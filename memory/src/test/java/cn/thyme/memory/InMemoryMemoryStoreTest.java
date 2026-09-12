package cn.thyme.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMemoryStoreTest {

    private InMemoryMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    private static void printRecords(String label, List<MemoryRecord> records) {
        System.out.printf("  >> [%s] %s%n", label, records.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("保存并读取一条记录")
    void savesAndGets() {
        store.save(MemoryRecord.of("a", "hello", Map.of("tag", "greeting")));
        print("保存 id=a, content=hello, metadata={tag=greeting}");

        Optional<MemoryRecord> result = store.get("a");
        print("get(a) -> " + result.map(MemoryRecord::content).orElse("<无>"));

        assertTrue(result.isPresent());
        assertEquals("hello", result.get().content());
        assertEquals("greeting", result.get().metadata().get("tag"));
    }

    @Test
    @DisplayName("保存同 id 覆盖旧记录")
    void upsertsById() {
        store.save(MemoryRecord.of("a", "old"));
        store.save(MemoryRecord.of("a", "new"));
        print("先后保存 id=a: old -> new");

        assertEquals("new", store.get("a").orElseThrow().content());
        assertEquals(1, store.list().size());
        print("get(a) -> new，总条数 " + store.list().size());
    }

    @Test
    @DisplayName("删除存在与不存在的记录")
    void deletesById() {
        store.save(MemoryRecord.of("a", "hello"));
        print("保存 id=a");

        boolean removed = store.delete("a");
        boolean removedAgain = store.delete("a");
        print("删除 a -> " + removed + "，再删一次 -> " + removedAgain);

        assertTrue(removed);
        assertFalse(removedAgain);
        assertTrue(store.get("a").isEmpty());
    }

    @Test
    @DisplayName("按 metadata 精确匹配查询")
    void queriesByEqFilter() {
        store.save(MemoryRecord.of("a", "note-a", Map.of("category", "note")));
        store.save(MemoryRecord.of("b", "note-b", Map.of("category", "note")));
        store.save(MemoryRecord.of("c", "task-c", Map.of("category", "task")));
        print("保存 3 条：a/b 是 note，c 是 task");

        List<MemoryRecord> notes = store.query(MemoryQuery.builder().eq("category", "note").build());
        printRecords("category=note", notes);

        assertEquals(List.of("a", "b"), notes.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("多条件 AND 查询")
    void queriesByMultipleFilters() {
        store.save(MemoryRecord.of("a", "x", Map.of("category", "note", "pinned", true)));
        store.save(MemoryRecord.of("b", "y", Map.of("category", "note")));
        print("保存 a（note+pinned）、b（仅 note）");

        List<MemoryRecord> result = store.query(MemoryQuery.builder()
                .eq("category", "note")
                .eq("pinned", true)
                .build());
        printRecords("category=note AND pinned=true", result);

        assertEquals(List.of("a"), result.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("分页查询")
    void queriesWithPagination() {
        for (int i = 0; i < 5; i++) {
            store.save(MemoryRecord.of("id-" + i, "v" + i, Map.of("g", "x")));
        }
        print("保存 5 条，全部 g=x");

        List<MemoryRecord> page = store.query(MemoryQuery.builder()
                .eq("g", "x")
                .offset(1)
                .limit(2)
                .build());
        printRecords("offset=1, limit=2", page);

        assertEquals(List.of("id-1", "id-2"), page.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("列出全部记录")
    void listsAll() {
        store.save(MemoryRecord.of("b", "2"));
        store.save(MemoryRecord.of("a", "1"));
        store.save(MemoryRecord.of("c", "3"));
        print("保存 3 条（顺序 b/a/c）");

        List<MemoryRecord> all = store.list();
        printRecords("list 全部（按 id 排序）", all);

        assertEquals(List.of("a", "b", "c"), all.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("按条件批量删除")
    void deletesWhere() {
        store.save(MemoryRecord.of("a", "1", Map.of("sessionId", "s1")));
        store.save(MemoryRecord.of("b", "2", Map.of("sessionId", "s1")));
        store.save(MemoryRecord.of("c", "3", Map.of("sessionId", "s2")));
        print("保存 3 条：a/b 属于 s1，c 属于 s2");

        int removed = store.deleteWhere(MemoryQuery.builder().eq("sessionId", "s1").build());
        print("删除 sessionId=s1 共 " + removed + " 条，剩余 " + store.list().size());

        assertEquals(2, removed);
        assertEquals(List.of("c"), store.list().stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("无筛选条件时拒绝批量删除，不误清空")
    void rejectsEmptyFilterDelete() {
        store.save(MemoryRecord.of("a", "1"));
        store.save(MemoryRecord.of("b", "2"));

        assertThrows(IllegalArgumentException.class, () -> store.deleteWhere(MemoryQuery.builder().build()));
        print("空条件被拒绝后仍有 " + store.list().size() + " 条，未被清空");

        assertEquals(2, store.list().size());
    }
}
