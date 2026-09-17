package cn.thyme.bootstrap;

import cn.thyme.memory.Session;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import cn.thyme.memory.db.MemoryDatabaseConfig;
import cn.thyme.memory.db.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 方言 SQL 的集成测试：用真实 SQLite 文件库跑通 session_record 的
 * 条件插入（ON CONFLICT DO NOTHING）、条件更新与倒序列表。只加载 memory-db
 * 上下文，不启动主 agent。
 */
@SpringBootTest(classes = SessionStoreSqliteIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "memory.store.type=database",
        "spring.datasource.url=jdbc:sqlite:./target/test-session.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "mybatis.mapper-locations=classpath*:/mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
class SessionStoreSqliteIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("cn.thyme.memory.db")
    @Import(MemoryDatabaseConfig.class)
    static class Config {
    }

    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(60);

    @Autowired
    private SessionStore store;

    @Autowired
    private SessionMapper mapper;

    @BeforeEach
    void clean() {
        store.list().forEach(s -> store.delete(s.id()));
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    @Test
    @DisplayName("SQLite: 条件插入，重复调用不覆盖")
    void insertIfAbsentDoesNotOverwrite() {
        assertTrue(store.createIfAbsent("s1", "占位标题", TitleSource.AUTO, T0));
        boolean second = store.createIfAbsent("s1", "第二次占位", TitleSource.AUTO, T1);
        print("第二次 createIfAbsent(s1) -> " + second);

        assertFalse(second);
        Session session = store.get("s1").orElseThrow();
        assertEquals("占位标题", session.title());
        assertEquals(T0.toEpochMilli(), session.updatedAt().toEpochMilli(), "创建时刻不得被第二次调用推进");
    }

    @Test
    @DisplayName("SQLite: epoch 毫秒原样往返")
    void timestampsRoundTrip() {
        store.createIfAbsent("s1", "会话", TitleSource.AUTO, T0);

        Session session = store.get("s1").orElseThrow();
        print("往返后的 createdAt -> " + session.createdAt());

        assertEquals(T0, session.createdAt());
        assertEquals(T0, session.updatedAt());
    }

    @Test
    @DisplayName("SQLite: 列表按 updated_at 倒序、同毫秒按 id 倒序")
    void listsDescending() {
        // 同一毫秒的三个会话：验证 id 兜底键真的生效（否则顺序不稳定）
        store.createIfAbsent("a", "A", TitleSource.AUTO, T0);
        store.createIfAbsent("b", "B", TitleSource.AUTO, T0);
        store.createIfAbsent("c", "C", TitleSource.AUTO, T0);
        store.createIfAbsent("newest", "最新", TitleSource.AUTO, T1);

        List<String> ids = store.list().stream().map(Session::id).toList();
        print("list 顺序 -> " + ids);

        assertEquals(List.of("newest", "c", "b", "a"), ids);
    }

    @Test
    @DisplayName("SQLite: 改名写 USER 来源，推进 updated_at")
    void renamesToUser() {
        store.createIfAbsent("s1", "占位", TitleSource.AUTO, T0);

        assertTrue(store.rename("s1", "用户改的", T1));
        Session session = store.get("s1").orElseThrow();
        print("改名后 -> " + session.title() + " / " + session.titleSource().wire());

        assertEquals("用户改的", session.title());
        assertEquals(TitleSource.USER, session.titleSource());
        assertEquals(T1, session.updatedAt());
        assertEquals(T0, session.createdAt(), "createdAt 不随更新变化");
    }

    @Test
    @DisplayName("SQLite: 条件更新只在 title_source=auto 上生效")
    void updateTitleIfAutoIsConditional() {
        store.createIfAbsent("s1", "占位", TitleSource.AUTO, T0);

        assertTrue(store.updateTitleIfAuto("s1", "模型生成的", TitleSource.GENERATED, T1));
        // 第二次：来源已是 generated，条件不再成立
        assertFalse(store.updateTitleIfAuto("s1", "第二次生成", TitleSource.GENERATED, T1));
        print("第二次 updateTitleIfAuto -> false");
        assertEquals("模型生成的", store.get("s1").orElseThrow().title());

        // 用户改名后同样被拒
        store.rename("s1", "用户改的", T1);
        assertFalse(store.updateTitleIfAuto("s1", "第三次生成", TitleSource.GENERATED, T1));
        print("改名后 updateTitleIfAuto -> false");
        assertEquals("用户改的", store.get("s1").orElseThrow().title());
    }

    @Test
    @DisplayName("SQLite: 未知来源值回落 auto 而不抛")
    void unknownSourceFallsBackToAuto() {
        // 直接经 Mapper 写入一个当前版本不认识的来源值，模拟脏行 / 回滚残留
        mapper.insertIfAbsent("dirty", "脏行", "future_value", T0.toEpochMilli(), T0.toEpochMilli());

        Session session = store.get("dirty").orElseThrow();
        print("脏行的 titleSource -> " + session.titleSource());

        assertEquals(TitleSource.AUTO, session.titleSource());
        // 关键：单条脏行不得让整个列表炸掉
        assertEquals(1, store.list().size());
    }

    @Test
    @DisplayName("SQLite: 删除命中一次")
    void deletesOnce() {
        store.createIfAbsent("s1", "会话", TitleSource.AUTO, T0);

        assertTrue(store.delete("s1"));
        assertFalse(store.delete("s1"));
        assertTrue(store.get("s1").isEmpty());
        print("delete(s1) -> true / 再删 -> false");
    }

    @Test
    @DisplayName("SQLite: 并发条件插入只有一个成功")
    void concurrentCreateOnlyOneWins() throws Exception {
        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (store.createIfAbsent("race", "标题-" + index, TitleSource.AUTO, T0)) {
                        created.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        print(threads + " 个线程并发 createIfAbsent，成功数 -> " + created.get());

        assertEquals(1, created.get(), "条件插入必须只有一个线程报告「新建」");
        assertEquals(1, store.list().size());
    }
}
