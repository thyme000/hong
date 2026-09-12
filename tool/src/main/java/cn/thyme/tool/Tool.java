package cn.thyme.tool;

import cn.thyme.tool.security.Capability;

import java.util.Map;
import java.util.Set;

/**
 * 工具的统一定义。内部工具直接实现本接口；MCP 工具通过适配器包装成本接口，
 * 由适配器内部调用 MCP 客户端。execute 本身是同步阻塞的——异步与否由
 * {@link #executionMode()} 声明，交给门面决定在哪个线程上运行。
 */
public interface Tool {

    /** 唯一标识。 */
    String name();

    /** 给 LLM 看的一句话说明。 */
    String description();

    /** 执行模式：只能同步、只能异步，或两者皆可。 */
    ExecutionMode executionMode();

    /** 入参的 JSON Schema（Map 形式，与 MCP 对齐）。 */
    Map<String, Object> inputSchema();

    /** 工具声明的能力（客观事实），供安全策略评估风险。默认无能力。 */
    default Set<Capability> capabilities() {
        return Set.of();
    }

    /** 执行工具并返回统一结果。context 提供本次调用的环境（如所属会话）。 */
    ToolResult execute(Map<String, Object> args, ToolContext context);
}
