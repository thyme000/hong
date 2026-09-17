package cn.thyme.memory.db;

import cn.thyme.memory.SessionStore;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 数据库实现的 Spring 配置：按数据库厂商识别 databaseId，
 * 供 mapper XML 用 databaseId 区分方言 SQL。
 * 由装配层在需要时 {@code @Import}（memory.store.type 非 inmemory）。
 */
@Configuration
public class MemoryDatabaseConfig {

    /**
     * 会话元信息存储。刻意不加 {@code @Component}：本包不在 bootstrap 的组件扫描
     * 范围内，加了会在 {@code @Import} 了本配置的测试上下文里看似可用、
     * 在别的上下文里静默缺失。
     */
    @Bean
    public SessionStore sessionStore(SessionMapper mapper) {
        return new DatabaseSessionStore(mapper);
    }


    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties props = new Properties();
        props.setProperty("H2", "h2");
        props.setProperty("PostgreSQL", "postgresql");
        props.setProperty("SQLite", "sqlite");
        provider.setProperties(props);
        return provider;
    }
}
