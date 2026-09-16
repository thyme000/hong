package cn.thyme.agent;

import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolResult;
import cn.thyme.tool.security.Capability;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 子 agent 工具：把一个 agent 包装成 {@link Tool}，注册进主 agent 的工具集，
 * 主 agent 通过工具调用委派任务给子 agent。
 *
 * <p>预算归属（划拨语义）：从主 agent 的 {@link AgentBudget} 里按档位切一块给子 agent，
 * 子 agent 上限被钉死在「划拨时刻已用量 + 划拨量」上。因为共享的是<b>实际用量</b>而非
 * 预留量，子 agent 提前结束时没花掉的额度自然还给主 agent。</p>
 *
 * <p>上下文隔离：global 由本工具持有（所有子 agent 共享的目标），task 与附加上下文
 * 由主 agent 每次调用传入（委派时传递），子 agent 的私有历史每次全新创建（互不干扰）。</p>
 */
public class SubAgentTool implements Tool {

    /** 委派门槛：写进工具描述，抑制主 agent 为琐碎任务启动子 agent。 */
    private static final String DELEGATION_GUIDE = """
            仅当任务足够复杂时才委派：需要多轮工具往返、独立探索、或大量阅读，\
            用子 agent 隔离上下文才划算。子 agent 有固定的启动开销（重建上下文、\
            重新读取文件、重新推导状态），因此一两步就能完成的任务请你自己直接做。""";

    private final String name;
    private final String description;
    private final AgentFactoryFn agentFactory;
    private final String global;

    /**
     * @param agentFactory 按划拨预算造子 agent；参数为 null 表示无父级预算，
     *                     由子 agent 按自身 maxTokens 自建
     */
    public SubAgentTool(String name, String description, AgentFactoryFn agentFactory, String global) {
        this.name = name;
        this.description = description;
        this.agentFactory = agentFactory;
        this.global = global == null ? "" : global;
    }

    /** 从划拨到的预算造出一个子 agent。 */
    @FunctionalInterface
    public interface AgentFactoryFn {
        Agent create(AgentBudget budget);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description + " " + DELEGATION_GUIDE;
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SYNC_ONLY;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.PROCESS_EXEC);
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string", "description", "子 agent 要执行的任务"),
                        "context", Map.of("type", "string", "description", "传递给子 agent 的附加上下文"),
                        "budget", Map.of(
                                "type", "string",
                                "enum", List.of("SMALL", "MEDIUM", "LARGE"),
                                "description", "任务体量档位，决定分给子 agent 的额度："
                                        + "SMALL=几步就完成的小任务，MEDIUM(默认)=需多轮工具往返，"
                                        + "LARGE=需广泛探索或大量阅读。无需精确，选个大概即可")),
                "required", List.of("task"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        Object taskObj = args.get("task");
        if (taskObj == null) {
            return ToolResult.failure("缺少必填参数: task");
        }
        String task = String.valueOf(taskObj);
        Object contextObj = args.get("context");
        String contextText = contextObj == null ? "" : String.valueOf(contextObj);
        SubAgentBudgetTier tier = SubAgentBudgetTier.parse(
                args.get("budget") == null ? null : String.valueOf(args.get("budget")));

        String fullTask = task;
        if (!contextText.isEmpty()) {
            fullTask = task + "\n\n附加上下文：\n" + contextText;
        }

        AgentBudget budget = childBudget(context, tier);
        if (budget == null && context != null && context.hasBudget()) {
            return ToolResult.failure("预算已耗尽，无法启动子 agent");
        }

        AgentContext subContext = AgentContext.builder()
                .global(global)
                .task(fullTask)
                // 历史用派生子会话（隔离父会话上下文）；
                // 工作区仍指向父会话，否则子 agent 看不到用户上传给父会话的文件
                .sessionId(subSessionId(context))
                .workspaceSessionId(context == null ? null : context.sessionId())
                .build();

        AgentResult result;
        try {
            result = agentFactory.create(budget).run(subContext);
        } catch (RuntimeException e) {
            // 子 agent 崩溃不应带垮主 agent：作为工具失败回报，让主 agent 自行决定如何继续
            return ToolResult.failure("子 agent 执行失败: " + e.getMessage());
        }

        if (result.truncated()) {
            // 被上限中断时没有最终答案，不能把空内容当成子 agent 的成果交给主 agent
            return ToolResult.failure("子 agent 未在上限内给出最终答案（" + result.stopReason() + "）");
        }
        return ToolResult.success(result.content(), result);
    }

    /**
     * 从主 agent 的预算里划拨一份子预算。
     * 主 agent 没有预算（脱离 agent 直接调用）时返回 null，交由子 agent 按自身配置自建。
     */
    private AgentBudget childBudget(ToolContext context, SubAgentBudgetTier tier) {
        if (context == null || !(context.budget() instanceof AgentBudget parent)) {
            return null;
        }
        int available = parent.remaining();
        if (available <= 0) {
            return null;
        }
        return parent.fork(tier.grantFrom(available));
    }

    /**
     * 派生子会话 id：子 agent 的事件与记忆挂在主会话之下，便于追溯
     * 「哪次委派产生的这些调用」，同时与主会话的消息序列隔离。
     *
     * <p>分隔符用 {@code -sub-} 而非 {@code /}：会话 id 会被当作沙箱目录名，
     * 含路径分隔符就成了越界隐患，故此处只产出安全字符。</p>
     */
    private static String subSessionId(ToolContext context) {
        String parent = context == null ? null : context.sessionId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return parent == null || parent.isEmpty() ? "sub-" + suffix : parent + "-sub-" + suffix;
    }
}
