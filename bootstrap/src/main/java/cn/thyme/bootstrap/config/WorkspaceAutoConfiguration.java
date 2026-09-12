package cn.thyme.bootstrap.config;

import cn.thyme.bootstrap.memory.HistoryRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 历史回注渲染器的装配。{@code SessionWorkspace} 自带 {@code @Component}，
 * 由组件扫描托管（根目录从配置项 {@code tool.workspace.dir} 注入）。
 */
@Configuration
public class WorkspaceAutoConfiguration {

    @Bean
    public HistoryRenderer historyRenderer() {
        return new HistoryRenderer();
    }
}
