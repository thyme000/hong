package cn.thyme.bootstrap.config;

import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import cn.thyme.memory.db.DatabaseSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库模式下的存储装配：{@code memory.store.type=sqlite} 时 {@link SessionStore}
 * 是数据库实现，且<b>建表已经发生过</b>——这里的断言会真的写一行，从而依赖
 * {@code session_record} 表存在。
 *
 * <p>它同时钉住 {@code memoryStore} 的构造顺序：建表是那个 bean 构造的副作用，
 * 而 {@code sessionStore} 是它的构造入参。若有人把那个参数去掉、又让 spring
 * 先创建了 sessionStore，这里的写入会因「表不存在」而失败。</p>
 */
@SpringBootTest(classes = SessionStoreWiringDatabaseTest.Config.class)
@TestPropertySource(properties = {
        "memory.store.type=sqlite",
        "spring.datasource.url=jdbc:sqlite:./target/test-session-wiring.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "mybatis.mapper-locations=classpath*:/mapper/**/*.xml"
})
class SessionStoreWiringDatabaseTest {

    @Configuration
    @EnableAutoConfiguration
    @Import(MemoryAutoConfiguration.class)
    static class Config {
    }

    @Autowired
    private SessionStore sessionStore;

    @Test
    @DisplayName("数据库模式：SessionStore 是数据库实现且建表已生效")
    void wiresDatabaseImplementationWithTableCreated() {
        System.out.println(">> sessionStore = " + sessionStore.getClass().getSimpleName());

        assertInstanceOf(DatabaseSessionStore.class, sessionStore);

        // id 每次跑都不一样：sqlite 文件在 target/ 下跨轮次保留，写死 id 的话
        // 第二轮起 createIfAbsent 会因为「行已存在」而返回 false，测试从此恒失败
        String id = "wiring-" + UUID.randomUUID();

        // 写一行成功即证明 session_record 已建好；表不存在会抛 SQLException
        assertTrue(sessionStore.createIfAbsent(id, "接线测试", TitleSource.AUTO, Instant.now()));
        assertTrue(sessionStore.get(id).isPresent());
        // 同一 id 再建一次不该覆盖，也不该报错——顺带钉住 createIfAbsent 的语义
        assertFalse(sessionStore.createIfAbsent(id, "别的标题", TitleSource.USER, Instant.now()));
        assertEquals("接线测试", sessionStore.get(id).orElseThrow().title());

        sessionStore.delete(id);
    }
}
