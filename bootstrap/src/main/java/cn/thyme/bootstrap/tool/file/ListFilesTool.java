package cn.thyme.bootstrap.tool.file;

import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.bootstrap.workspace.WorkspaceException;
import cn.thyme.bootstrap.workspace.WorkspaceFile;
import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolMark;
import cn.thyme.tool.ToolResult;
import cn.thyme.tool.security.Capability;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列文件工具：列出当前会话沙箱内用户已上传的文件，返回 JSON 数组，
 * 每项含原始名与字节大小。模型据此知道有哪些文件可读，再用 read_file 读取。
 */
@Component
@ToolMark(category = "file", action = "list")
public class ListFilesTool implements Tool {

    private final SessionWorkspace workspace;

    public ListFilesTool(SessionWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "列出当前会话中用户已上传的全部文件（名称与大小）。";
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SYNC_ONLY;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.FILE_READ);
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        if (!context.hasSession()) {
            return ToolResult.failure("缺少会话上下文，无法定位沙箱");
        }
        try {
            List<WorkspaceFile> files = workspace.list(context.sessionId());
            List<Map<String, Object>> payload = files.stream()
                    .map(f -> Map.<String, Object>of("name", f.name(), "size", f.size()))
                    .toList();
            return ToolResult.success(toJson(payload), payload);
        } catch (WorkspaceException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    /** 手写 JSON 数组，避免为几个字段引入序列化依赖。 */
    private static String toJson(List<Map<String, Object>> files) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> file = files.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(escape(String.valueOf(file.get("name"))))
                    .append("\",\"size\":").append(file.get("size")).append('}');
        }
        return sb.append(']').toString();
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
