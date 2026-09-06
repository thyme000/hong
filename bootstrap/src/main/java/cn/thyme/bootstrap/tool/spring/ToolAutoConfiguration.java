package cn.thyme.bootstrap.tool.spring;

import cn.thyme.tool.DefaultToolManager;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolDescriptor;
import cn.thyme.tool.ToolManager;
import cn.thyme.tool.ToolMark;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring 适配：把容器里的全部 {@link Tool} bean 自动注册进门面，
 * 并暴露一个单例 {@link ToolManager}。工具类本身用 {@code @Component}
 * （或 {@code @Bean}）交给 Spring 管理，标记信息来自 {@link ToolMark} 注解。
 */
@Configuration
public class ToolAutoConfiguration {

    @Bean
    public ToolManager toolManager(List<Tool> tools) {
        DefaultToolManager manager = new DefaultToolManager();
        for (Tool tool : tools) {
            manager.internalRegistry().register(tool, ToolDescriptor.fromTool(tool));
        }
        return manager;
    }
}
