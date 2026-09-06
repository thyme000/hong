package cn.thyme.tool.registry;

import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolDescriptor;
import cn.thyme.tool.ToolResult;
import cn.thyme.tool.ToolSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 MCP client 的工具注册表：连接远端 MCP server，把其 tools/list 包装成
 * 统一的 {@link Tool}，随连接的建立/断开而动态上下线。
 * 远端工具的标记默认按服务器名归类（category = 服务器名，action = invoke）。
 */
public class McpToolRegistry implements ToolRegistry {

    private final String serverName;
    private final McpClientTransport transport;
    private final InMemoryToolRegistry delegate = new InMemoryToolRegistry();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private McpSyncClient client;

    public McpToolRegistry(String serverName, McpClientTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /** 使用 Streamable HTTP 连接远端 MCP server。 */
    public static McpToolRegistry http(String serverName, String endpoint) {
        return new McpToolRegistry(serverName,
                HttpClientStreamableHttpTransport.builder(endpoint).build());
    }

    /** 使用 stdio 启动本地子进程作为 MCP server。 */
    public static McpToolRegistry stdio(String serverName, String command, List<String> args) {
        ServerParameters params = ServerParameters.builder(command).args(args).build();
        return new McpToolRegistry(serverName,
                new StdioClientTransport(params, McpJsonDefaults.getMapper()));
    }

    /** 建立连接并注册全部远端工具。 */
    public void connect() {
        if (!connected.compareAndSet(false, true)) {
            return;
        }
        McpSyncClient c = McpClient.sync(transport).build();
        c.initialize();
        for (McpSchema.Tool t : c.listTools().tools()) {
            delegate.register(new McpToolAdapter(t, c), descriptor(t));
        }
        this.client = c;
    }

    /** 断开连接并注销全部工具。 */
    public void close() {
        if (!connected.compareAndSet(true, false)) {
            return;
        }
        delegate.list().forEach(d -> delegate.unregister(d.name()));
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private ToolDescriptor descriptor(McpSchema.Tool t) {
        return new ToolDescriptor(
                t.name(),
                t.description() == null ? "" : t.description(),
                ToolSource.MCP,
                ExecutionMode.ASYNC_ONLY,
                serverName,
                "invoke",
                Set.of(),
                toMap(t.inputSchema()));
    }

    private static Map<String, Object> toMap(McpSchema.JsonSchema s) {
        if (s == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (s.type() != null) {
            m.put("type", s.type());
        }
        if (s.properties() != null) {
            m.put("properties", s.properties());
        }
        if (s.required() != null) {
            m.put("required", s.required());
        }
        return m;
    }

    @Override
    public void register(Tool tool, ToolDescriptor descriptor) {
        delegate.register(tool, descriptor);
    }

    @Override
    public void unregister(String name) {
        delegate.unregister(name);
    }

    @Override
    public Optional<Tool> get(String name) {
        return delegate.get(name);
    }

    @Override
    public Optional<ToolDescriptor> describe(String name) {
        return delegate.describe(name);
    }

    @Override
    public List<ToolDescriptor> list() {
        return delegate.list();
    }

    /** 远端工具适配器：把 MCP 工具包装成统一的 {@link Tool}，执行时转发给 MCP client。 */
    private static final class McpToolAdapter implements Tool {

        private final McpSchema.Tool remote;
        private final McpSyncClient client;

        McpToolAdapter(McpSchema.Tool remote, McpSyncClient client) {
            this.remote = remote;
            this.client = client;
        }

        @Override
        public String name() {
            return remote.name();
        }

        @Override
        public String description() {
            return remote.description() == null ? "" : remote.description();
        }

        @Override
        public ExecutionMode executionMode() {
            return ExecutionMode.ASYNC_ONLY;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return toMap(remote.inputSchema());
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            McpSchema.CallToolResult r = client.callTool(
                    McpSchema.CallToolRequest.builder()
                            .name(remote.name())
                            .arguments(args)
                            .build());
            String text = extractText(r);
            if (Boolean.TRUE.equals(r.isError())) {
                return ToolResult.failure(text);
            }
            return ToolResult.success(text, r.structuredContent());
        }

        private static String extractText(McpSchema.CallToolResult r) {
            List<McpSchema.Content> content = r.content();
            if (content == null || content.isEmpty()) {
                return r.structuredContent() == null ? "" : String.valueOf(r.structuredContent());
            }
            StringBuilder sb = new StringBuilder();
            for (McpSchema.Content c : content) {
                if (c instanceof McpSchema.TextContent tc) {
                    sb.append(tc.text());
                }
            }
            return sb.toString();
        }
    }
}
