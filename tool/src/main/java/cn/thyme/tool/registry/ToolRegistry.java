package cn.thyme.tool.registry;

import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * 工具注册表，保存某类来源的工具及其描述。内部工具与 MCP 工具各持有一个实现，
 * 门面把两者的结果合并后统一对外。
 */
public interface ToolRegistry {

    void register(Tool tool, ToolDescriptor descriptor);

    void unregister(String name);

    Optional<Tool> get(String name);

    Optional<ToolDescriptor> describe(String name);

    List<ToolDescriptor> list();
}
