package cn.thyme.tool;

import cn.thyme.tool.registry.InMemoryToolRegistry;
import cn.thyme.tool.registry.ToolRegistry;
import cn.thyme.tool.selector.DefaultToolSelector;
import cn.thyme.tool.selector.ToolSelector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 门面默认实现：合并内部工具与 MCP 工具，统一对外提供 describe/select/execute。
 * 执行时按工具的 {@link ExecutionMode} 决定在调用线程还是默认执行器上运行，
 * 并支持超时。
 */
public class DefaultToolManager implements ToolManager {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ToolRegistry internal;
    private final ToolRegistry mcp;
    private final ToolSelector selector;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public DefaultToolManager() {
        this(new InMemoryToolRegistry(), new InMemoryToolRegistry(),
                new DefaultToolSelector(), null);
    }

    public DefaultToolManager(ToolRegistry internal, ToolRegistry mcp,
                              ToolSelector selector, ExecutorService executor) {
        this.internal = internal;
        this.mcp = mcp;
        this.selector = selector;
        if (executor == null) {
            this.executor = new ThreadPoolExecutor(
                    4,
                    16,
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100),
                    r -> {
                        Thread t = new Thread(r);
                        t.setName("tool-executor-" + t.threadId());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy());
            this.ownsExecutor = true;
        } else {
            this.executor = executor;
            this.ownsExecutor = false;
        }
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
    public ToolResult execute(String name, Map<String, Object> args) {
        return execute(name, args, DEFAULT_TIMEOUT);
    }

    /** 按名称执行工具，指定超时；同步阻塞等待结果。 */
    public ToolResult execute(String name, Map<String, Object> args, Duration timeout) {
        Optional<Tool> tool = resolve(name);
        if (tool.isEmpty()) {
            return ToolResult.failure("工具不存在: " + name);
        }
        ToolDescriptor descriptor = internal.describe(name)
                .or(() -> mcp.describe(name))
                .orElseThrow();

        // 只能同步的工具在当前线程直接执行，无法被超时中断。
        if (descriptor.executionMode() == ExecutionMode.SYNC_ONLY) {
            return run(tool.get(), args);
        }

        CompletableFuture<ToolResult> future =
                CompletableFuture.supplyAsync(() -> run(tool.get(), args), executor);
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

    private ToolResult run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    /** 关闭自建执行器；外部传入的执行器由调用方管理。 */
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
