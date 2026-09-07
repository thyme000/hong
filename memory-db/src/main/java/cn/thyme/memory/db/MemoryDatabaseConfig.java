package cn.thyme.memory.db;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 数据库实现的 Spring 配置：按数据库厂商识别 databaseId，
 * 供 mapper XML 用 databaseId 区分方言 SQL。
 */
@Configuration
@ConditionalOnProperty(name = "memory.store.type", havingValue = "database")
public class MemoryDatabaseConfig {

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
