package cn.thyme.bootstrap.config;

import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.InMemorySessionStore;
import cn.thyme.memory.MemoryStore;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.db.DatabaseMemoryStore;
import cn.thyme.memory.db.MemoryDatabaseConfig;
import cn.thyme.memory.db.MemoryMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆存储装配：按 {@code memory.store.type} 选择实现，只启动对应的一个。
 * <ul>
 *   <li>{@code inmemory}（默认）→ {@link InMemoryMemoryStore} + {@link InMemorySessionStore}，
 *       完全不接触数据库</li>
 *   <li>其它值（如 {@code sqlite} / {@code h2} / {@code postgresql}）→ {@link DatabaseMemoryStore}
 *       + {@link DatabaseSessionStore}</li>
 * </ul>
 * 消息历史与会话元信息是两个平级的存储抽象（{@code MemoryStore} / {@code SessionStore}），
 * 共用本开关。两个分支必须各自提供两个 bean，只给一边会让另一模式注入失败。
 * 启动类排除了 {@code DataSourceAutoConfiguration}，故数据库模式下的
 * DataSource 在此按需创建（并先确保 SQLite 文件目录存在），建表也在此完成。
 */
@Configuration
public class MemoryAutoConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "memory.store.type", havingValue = "inmemory", matchIfMissing = true)
    static class InMemoryConfig {
        @Bean
        MemoryStore memoryStore() {
            return new InMemoryMemoryStore();
        }

        @Bean
        SessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

    @Configuration
    @ConditionalOnExpression("!'inmemory'.equals('${memory.store.type:inmemory}')")
    @Import(MemoryDatabaseConfig.class)
    @MapperScan("cn.thyme.memory.db")
    static class DatabaseConfig {

        @Value("${spring.datasource.url}")
        private String url;

        @Bean
        DataSource dataSource() {
            prepareSqliteDirectory(url);
            return new DriverManagerDataSource(url);
        }

        /**
         * 建表目前是本 bean 构造的副作用（生产只加载 schema.sql 这一个资源，
         * 由这里执行）。{@code sessionStore} 作为构造入参<b>不是装饰</b>：它把
         * 「必须先建表」变成声明的依赖关系，而不是指望 Spring 恰好先创建本 bean。
         * 那种隐式顺序一旦被改（@Lazy、懒加载、按需创建），症状是运行期
         * 「表不存在」——而调用方会吞掉这个异常，表现为会话列表永远是空的。
         *
         * <p>{@code SessionStore} 的 bean 由上面 {@code @Import} 进来的
         * {@link MemoryDatabaseConfig} 提供（与 {@code SessionMapper} 同模块），
         * 此处<b>不再重复定义</b>：同一上下文里两个同名 bean 会直接启动失败。</p>
         */
        @Bean
        MemoryStore memoryStore(DataSource dataSource, MemoryMapper mapper, ObjectMapper json,
                                SessionStore sessionStore) {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
            return new DatabaseMemoryStore(mapper, json);
        }

        private static void prepareSqliteDirectory(String url) {
            String prefix = "jdbc:sqlite:";
            if (url == null || !url.startsWith(prefix) || url.contains(":memory:")) {
                return;
            }
            Path parent = Path.of(url.substring(prefix.length())).toAbsolutePath().getParent();
            if (parent == null) {
                return;
            }
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("创建 SQLite 数据目录失败: " + parent, e);
            }
        }
    }
}
