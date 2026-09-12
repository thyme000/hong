package cn.thyme.tool;

import cn.thyme.tool.security.Capability;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具在运行时的完整描述，注册表与选择器据此工作。
 * 由内部工具的注解或 MCP 工具的配置归一而成。
 */
public record ToolDescriptor(
        String name,
        String description,
        ToolSource source,
        ExecutionMode executionMode,
        String category,
        String action,
        Set<String> tags,
        Map<String, Object> inputSchema,
        Set<Capability> capabilities) {

    public ToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(executionMode, "executionMode must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(action, "action must not be null");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** 由 {@link Tool} 及其 {@link ToolMark} 注解构造描述。 */
    public static ToolDescriptor fromTool(Tool tool) {
        ToolMark mark = tool.getClass().getAnnotation(ToolMark.class);
        String category = mark == null ? "general" : mark.category();
        String action = mark == null ? "invoke" : mark.action();
        Set<String> tags = mark == null ? Set.of() : Set.of(mark.tags());
        return new ToolDescriptor(
                tool.name(),
                tool.description(),
                ToolSource.INTERNAL,
                tool.executionMode(),
                category,
                action,
                tags,
                tool.inputSchema(),
                tool.capabilities());
    }
}
