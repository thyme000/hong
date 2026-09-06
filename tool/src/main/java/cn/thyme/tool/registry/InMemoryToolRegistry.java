package cn.thyme.tool.registry;

import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现，线程安全，支持动态注册与移除。内部工具默认使用本实现。
 */
public class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool, ToolDescriptor descriptor) {
        tools.put(tool.name(), tool);
        descriptors.put(tool.name(), descriptor);
    }

    @Override
    public void unregister(String name) {
        tools.remove(name);
        descriptors.remove(name);
    }

    @Override
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Optional<ToolDescriptor> describe(String name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    @Override
    public List<ToolDescriptor> list() {
        return new ArrayList<>(descriptors.values());
    }
}
