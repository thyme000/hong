package cn.thyme.bootstrap.tool.sample;

import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolMark;
import cn.thyme.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例工具：把入参原样返回。演示内部工具如何通过 {@link Tool} 接口与
 * {@link ToolMark} 注解声明，并被 Spring 自动注册进工具门面。
 */
@Component
@ToolMark(category = "sample", action = "echo")
public class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "把输入文本原样返回。";
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SYNC_ONLY;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", java.util.List.of("text"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object text = args.get("text");
        return ToolResult.success(String.valueOf(text));
    }
}
