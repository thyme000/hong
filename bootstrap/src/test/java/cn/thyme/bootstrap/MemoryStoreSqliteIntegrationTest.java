package cn.thyme.bootstrap;

import cn.thyme.memory.MemoryQuery;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.db.DatabaseMemoryStore;
import cn.thyme.memory.db.MemoryDatabaseConfig;
import cn.thyme.memory.db.MemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 方言 SQL 的集成测试：用真实 SQLite 文件库跑通 upsert（ON CONFLICT）
 * 与 JSON 精确查询（json_extract）。只加载 memory-db 上下文，不启动主 agent。
 */
@SpringBootTest(classes = MemoryStoreSqliteIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "memory.store.type=database",
        "spring.datasource.url=jdbc:sqlite:./target/test-memory.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "mybatis.mapper-locations=classpath*:/mapper/**/*.xml"
})
class MemoryStoreSqliteIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("cn.thyme.memory.db")
    @Import(MemoryDatabaseConfig.class)
    static class Config {
        @Bean
        DatabaseMemoryStore databaseMemoryStore(MemoryMapper mapper, ObjectMapper json) {
            return new DatabaseMemoryStore(mapper, json);
        }
    }

    @Autowired
    private DatabaseMemoryStore store;

    @BeforeEach
    void clean() {
        store.list().forEach(r -> store.delete(r.id()));
    }

    @Test
    @DisplayName("SQLite: 保存并读取")
    void savesAndGets() {
        store.save(MemoryRecord.of("a", "hello", Map.of("tag", "greeting")));

        var result = store.get("a");
        System.out.println(">> get(a) = " + result.map(MemoryRecord::content).orElse("<无>"));

        assertTrue(result.isPresent());
        assertEquals("hello", result.get().content());
        assertEquals("greeting", result.get().metadata().get("tag"));
    }

    @Test
    @DisplayName("SQLite: upsert 覆盖同 id")
    void upserts() {
        store.save(MemoryRecord.of("u1", "old"));
        store.save(MemoryRecord.of("u1", "new"));

        System.out.println(">> upsert 后 get(u1) = " + store.get("u1").orElseThrow().content());
        assertEquals("new", store.get("u1").orElseThrow().content());
    }

    @Test
    @DisplayName("SQLite: JSON 精确匹配查询（json_extract）")
    void queriesByJson() {
        store.save(MemoryRecord.of("q1", "note-1", Map.of("category", "note")));
        store.save(MemoryRecord.of("q2", "task-1", Map.of("category", "task")));

        List<MemoryRecord> notes = store.query(MemoryQuery.builder().eq("category", "note").build());
        System.out.println(">> category=note 结果: " + notes.stream().map(MemoryRecord::id).toList());

        assertEquals(List.of("q1"), notes.stream().map(MemoryRecord::id).toList());
    }

    @Test
    @DisplayName("SQLite: 删除")
    void deletes() {
        store.save(MemoryRecord.of("d1", "x"));

        assertTrue(store.delete("d1"));
        assertTrue(store.get("d1").isEmpty());
    }

    @Test
    @DisplayName("SQLite: 按 JSON 字段条件批量删除（deleteWhere）")
    void deletesWhere() {
        store.save(MemoryRecord.of("m1", "a", Map.of("sessionId", "s-1")));
        store.save(MemoryRecord.of("m2", "b", Map.of("sessionId", "s-1")));
        store.save(MemoryRecord.of("m3", "c", Map.of("sessionId", "s-2")));

        int removed = store.deleteWhere(MemoryQuery.builder().eq("sessionId", "s-1").build());
        System.out.println(">> 删除 s-1 的记录数: " + removed);

        assertEquals(2, removed);
        assertTrue(store.get("m1").isEmpty());
        assertTrue(store.get("m2").isEmpty());
        assertTrue(store.get("m3").isPresent(), "其它会话的记录不应被删");
    }
}
