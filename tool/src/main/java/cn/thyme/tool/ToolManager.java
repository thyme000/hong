package cn.thyme.tool;

import cn.thyme.tool.registry.ToolRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 工具门面：对外的唯一入口。合并内部工具与外部 MCP 工具，
 * 提供"有哪些工具"（{@link #describe()}）、按任务选工具（{@link #select(TaskContext)}）
 * 与执行（{@link #execute(String, java.util.Map, ToolContext)}）。
 */
public interface ToolManager {

    /** 暴露底层注册表，供 Spring 适配或调用方直接注册工具。 */
    ToolRegistry internalRegistry();

    /** 暴露 MCP 注册表，供 MCP 连接适配层注册/注销工具。 */
    ToolRegistry mcpRegistry();

    /** 当前在线的全部工具。 */
    List<ToolInfo> describe();

    /** 按任务需求选择工具。 */
    List<ToolInfo> select(TaskContext task);

    /** 按名称执行工具（同步等待结果），不带会话上下文。 */
    default ToolResult execute(String name, java.util.Map<String, Object> args) {
        return execute(name, args, ToolContext.none());
    }

    /** 按名称执行工具（同步等待结果），context 提供本次调用的环境（如所属会话）。 */
    ToolResult execute(String name, java.util.Map<String, Object> args, ToolContext context);

    /** 按名称查找工具信息。 */
    Optional<ToolInfo> find(String name);
}
