package cn.thyme.tool;

import java.util.Map;
import java.util.Set;

/**
 * 对外呈现的工具信息视图，用于"现在有哪些工具"与喂给 LLM 的工具清单。
 */
public record ToolInfo(
        String name,
        String description,
        ToolSource source,
        ExecutionMode executionMode,
        String category,
        String action,
        Set<String> tags,
        Map<String, Object> inputSchema) {

    static ToolInfo of(ToolDescriptor d) {
        return new ToolInfo(
                d.name(), d.description(), d.source(), d.executionMode(),
                d.category(), d.action(), d.tags(), d.inputSchema());
    }
}
