package cn.thyme.bootstrap.config;

import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.MemoryStore;
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
 *   <li>{@code inmemory}（默认）→ {@link InMemoryMemoryStore}，完全不接触数据库</li>
 *   <li>其它值（如 {@code sqlite} / {@code h2} / {@code postgresql}）→ {@link DatabaseMemoryStore}</li>
 * </ul>
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

        @Bean
        MemoryStore memoryStore(DataSource dataSource, MemoryMapper mapper, ObjectMapper json) {
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
