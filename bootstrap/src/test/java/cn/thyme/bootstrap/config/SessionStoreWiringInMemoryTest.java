package cn.thyme.bootstrap.config;

import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.InMemorySessionStore;
import cn.thyme.memory.MemoryStore;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存模式下的存储装配：{@code memory.store.type=inmemory}（缺省值）时
 * 两个存储都得是内存实现，且都必须真的可用。
 *
 * <p>与数据库分支的装配测试分成两个文件：{@code @TestPropertySource} 是类级注解
 * 且不可嵌套，两种模式没法写在同一个文件里。</p>
 */
@SpringBootTest(classes = SessionStoreWiringInMemoryTest.Config.class)
@TestPropertySource(properties = "memory.store.type=inmemory")
class SessionStoreWiringInMemoryTest {

    @Configuration
    @EnableAutoConfiguration
    @Import(MemoryAutoConfiguration.class)
    static class Config {
    }

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private SessionStore sessionStore;

    @Test
    @DisplayName("内存模式：两个存储都是内存实现且可用")
    void wiresInMemoryImplementations() {
        System.out.println(">> memoryStore  = " + memoryStore.getClass().getSimpleName());
        System.out.println(">> sessionStore = " + sessionStore.getClass().getSimpleName());

        assertInstanceOf(InMemoryMemoryStore.class, memoryStore);
        assertInstanceOf(InMemorySessionStore.class, sessionStore);

        // 不只是「注入成功」——真的写一次，确认它能用
        assertTrue(sessionStore.createIfAbsent("s1", "标题", TitleSource.AUTO, Instant.now()));
        assertTrue(sessionStore.get("s1").isPresent());
    }
}
