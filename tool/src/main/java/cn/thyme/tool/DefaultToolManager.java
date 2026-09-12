package cn.thyme.tool;

import cn.thyme.tool.registry.InMemoryToolRegistry;
import cn.thyme.tool.registry.ToolRegistry;
import cn.thyme.tool.security.ApprovalHandler;
import cn.thyme.tool.security.DefaultToolSecurityPolicy;
import cn.thyme.tool.security.IsolationLevel;
import cn.thyme.tool.security.SecurityDecision;
import cn.thyme.tool.security.ToolSecurityPolicy;
import cn.thyme.tool.selector.DefaultToolSelector;
import cn.thyme.tool.selector.ToolSelector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 门面默认实现：合并内部工具与 MCP 工具，统一对外提供 describe/select/execute。
 * 执行前由 {@link ToolSecurityPolicy} 评估安全决策，按动作（允许/确认/拒绝）
 * 与隔离级别选择执行环境，并支持超时。
 */
public class DefaultToolManager implements ToolManager {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ToolRegistry internal;
    private final ToolRegistry mcp;
    private final ToolSelector selector;
    private final ExecutorService executor;
    private final ExecutorService isolatedExecutor;
    private final boolean ownsExecutor;

    private ToolSecurityPolicy securityPolicy = new DefaultToolSecurityPolicy();
    private ApprovalHandler approvalHandler;

    public DefaultToolManager() {
        this(new InMemoryToolRegistry(), new InMemoryToolRegistry(),
                new DefaultToolSelector(), null);
    }

    public DefaultToolManager(ToolRegistry internal, ToolRegistry mcp,
                              ToolSelector selector, ExecutorService executor) {
        this.internal = internal;
        this.mcp = mcp;
        this.selector = selector;
        this.ownsExecutor = executor == null;
        this.executor = executor == null ? newExecutor("tool-executor") : executor;
        this.isolatedExecutor = newExecutor("tool-isolated-executor");
    }

    private static ExecutorService newExecutor(String name) {
        return new ThreadPoolExecutor(
                4,
                16,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(name + "-" + t.threadId());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void setSecurityPolicy(ToolSecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    public void setApprovalHandler(ApprovalHandler approvalHandler) {
        this.approvalHandler = approvalHandler;
    }

    @Override
    public ToolRegistry internalRegistry() {
        return internal;
    }

    @Override
    public ToolRegistry mcpRegistry() {
        return mcp;
    }

    @Override
    public List<ToolInfo> describe() {
        List<ToolInfo> result = new ArrayList<>();
        internal.list().forEach(d -> result.add(ToolInfo.of(d)));
        mcp.list().forEach(d -> result.add(ToolInfo.of(d)));
        return List.copyOf(result);
    }

    @Override
    public List<ToolInfo> select(TaskContext task) {
        List<ToolDescriptor> candidates = new ArrayList<>();
        candidates.addAll(internal.list());
        candidates.addAll(mcp.list());
        return selector.select(task, candidates).stream().map(ToolInfo::of).toList();
    }

    @Override
    public Optional<ToolInfo> find(String name) {
        Optional<ToolDescriptor> d = internal.describe(name);
        if (d.isEmpty()) {
            d = mcp.describe(name);
        }
        return d.map(ToolInfo::of);
    }

    @Override
    public ToolResult execute(String name, Map<String, Object> args, ToolContext context) {
        return execute(name, args, context, DEFAULT_TIMEOUT);
    }

    /** 按名称执行工具，指定超时；同步阻塞等待结果。 */
    public ToolResult execute(String name, Map<String, Object> args, ToolContext context, Duration timeout) {
        Optional<Tool> tool = resolve(name);
        if (tool.isEmpty()) {
            return ToolResult.failure("工具不存在: " + name);
        }
        ToolDescriptor descriptor = internal.describe(name)
                .or(() -> mcp.describe(name))
                .orElseThrow();

        SecurityDecision decision = securityPolicy.evaluate(descriptor.capabilities(), args, context);
        switch (decision.action()) {
            case DENY -> {
                return ToolResult.failure("工具被安全策略拒绝: " + name);
            }
            case CONFIRM -> {
                if (approvalHandler == null || !approvalHandler.requestApproval(name, args)) {
                    return ToolResult.failure("用户未确认执行工具: " + name);
                }
            }
            case ALLOW -> {
            }
        }

        // 无隔离且只能同步的工具，在当前线程直接执行；其余走线程池（THREAD/PROCESS 用隔离池）。
        if (decision.isolation() == IsolationLevel.NONE
                && descriptor.executionMode() == ExecutionMode.SYNC_ONLY) {
            return run(tool.get(), args, context);
        }

        ExecutorService target = decision.isolation() == IsolationLevel.NONE ? executor : isolatedExecutor;
        CompletableFuture<ToolResult> future =
                CompletableFuture.supplyAsync(() -> run(tool.get(), args, context), target);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.failure("工具执行超时: " + name);
        } catch (Exception e) {
            return ToolResult.failure("工具执行失败: " + e.getMessage());
        }
    }

    private Optional<Tool> resolve(String name) {
        Optional<Tool> t = internal.get(name);
        return t.isPresent() ? t : mcp.get(name);
    }

    private ToolResult run(Tool tool, Map<String, Object> args, ToolContext context) {
        try {
            return tool.execute(args, context);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    /** 关闭自建执行器；外部传入的执行器由调用方管理。 */
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdown();
            isolatedExecutor.shutdown();
        }
    }
}
