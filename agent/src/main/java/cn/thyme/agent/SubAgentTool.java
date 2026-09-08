package cn.thyme.agent;

import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 子 agent 工具：把一个 agent 包装成 {@link Tool}，注册进主 agent 的工具集，
 * 主 agent 通过工具调用委派任务给子 agent。
 *
 * 上下文隔离：global 由本工具持有（所有子 agent 共享的目标），task 与附加上下文
 * 由主 agent 每次调用传入（委派时传递），子 agent 的私有历史每次全新创建（互不干扰）。
 */
public class SubAgentTool implements Tool {

    private final String name;
    private final String description;
    private final Agent subAgent;
    private final String global;

    public SubAgentTool(String name, String description, Agent subAgent, String global) {
        this.name = name;
        this.description = description;
        this.subAgent = subAgent;
        this.global = global == null ? "" : global;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SYNC_ONLY;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string", "description", "子 agent 要执行的任务"),
                        "context", Map.of("type", "string", "description", "传递给子 agent 的附加上下文")),
                "required", List.of("task"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object taskObj = args.get("task");
        if (taskObj == null) {
            return ToolResult.failure("缺少必填参数: task");
        }
        String task = String.valueOf(taskObj);
        Object contextObj = args.get("context");
        String context = contextObj == null ? "" : String.valueOf(contextObj);

        String fullTask = task;
        if (!context.isEmpty()) {
            fullTask = task + "\n\n附加上下文：\n" + context;
        }

        AgentContext subContext = AgentContext.builder()
                .global(global)
                .task(fullTask)
                .build();

        AgentResult result = subAgent.run(subContext);
        return ToolResult.success(result.content(), result);
    }
}
