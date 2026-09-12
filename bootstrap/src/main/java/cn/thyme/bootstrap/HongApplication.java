package cn.thyme.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * 启动类。排除 {@link DataSourceAutoConfiguration}：数据源只在
 * {@code memory.store.type} 为数据库时才需要，由装配层按需创建，
 * 避免内存模式下仍尝试连接数据库。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class HongApplication {

    public static void main(String[] args) {
        SpringApplication.run(HongApplication.class, args);
    }

}
