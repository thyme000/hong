package cn.thyme.bootstrap.tool.file;

import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.bootstrap.workspace.WorkspaceException;
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
 * 读文件工具：读取当前<b>会话沙箱</b>内指定文件的内容，支持按行范围分页与字符数截断。
 * 文件来自用户上传，用原始文件名寻址；沙箱由调用上下文里的 sessionId 定位。
 */
@Component
@ToolMark(category = "file", action = "read")
public class ReadFileTool implements Tool {

    private final SessionWorkspace workspace;

    public ReadFileTool(SessionWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取当前会话中已上传文件的内容。文件名用 list_files 返回的原始名，"
                + "支持按行范围分页与按字符数截断。";
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
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of("type", "string", "description", "文件名（用 list_files 返回的名字）"));
        properties.put("maxSize", Map.of("type", "integer", "description", "最大返回字符数，默认 10000"));
        properties.put("offset", Map.of("type", "integer", "description", "起始行号，从 1 开始"));
        properties.put("limit", Map.of("type", "integer", "description", "读取行数"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        Object path = args.get("path");
        if (path == null) {
            return ToolResult.failure("缺少必填参数: path");
        }
        if (!context.hasSession()) {
            return ToolResult.failure("缺少会话上下文，无法定位沙箱");
        }

        try {
            FileReadResult result = FileReadService.read(
                    workspace.readText(context.sessionId(), String.valueOf(path)),
                    toInt(args.get("maxSize")),
                    toInt(args.get("offset")),
                    toInt(args.get("limit")));

            String text = result.content();
            if (result.truncated()) {
                text += "\n[已截断：内容超过大小上限]";
            }
            return ToolResult.success(text, result);
        } catch (WorkspaceException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
