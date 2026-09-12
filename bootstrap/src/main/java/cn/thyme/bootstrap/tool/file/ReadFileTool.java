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
        return "读取当前会话中已上传文件的内容。不传 offset/limit 时返回全文；"
                + "需要分页时用 offset（起始行，从 1 开始）与 limit（读取行数）。"
                + "返回内容末尾会标明本次读取的行范围与文件总行数，据此判断是否已读完。";
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
            Integer offset = toInt(args.get("offset"));
            Integer limit = toInt(args.get("limit"));
            FileReadResult result = FileReadService.read(
                    workspace.readText(context.sessionId(), String.valueOf(path)),
                    toInt(args.get("maxSize")),
                    offset,
                    limit);

            return ToolResult.success(result.content() + footer(result, offset), result);
        } catch (WorkspaceException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    /**
     * 返回内容末尾的行范围说明。模型据此判断「是否已读到结尾」——
     * 缺少这个信号时，模型无法区分「全文就这么多」与「后面还有」，会反复重读同一文件。
     *
     * <p>截断与分页是两种不同的「没读完」：前者是字符数到了上限，该用更大的 maxSize 重读；
     * 后者是行范围没覆盖到末尾，该用 offset 续读。提示要区分开，否则会给出错误指引。</p>
     */
    private static String footer(FileReadResult result, Integer offset) {
        int total = result.totalLines();
        if (total == 0) {
            return "\n[文件为空]";
        }
        if (result.returnedLines() == 0) {
            return "\n[起始行 " + (offset == null ? 1 : offset) + " 超出文件末尾，共 " + total + " 行]";
        }

        int first = offset == null ? 1 : offset;
        String range = "本次返回第 " + first + "-" + (first + result.returnedLines() - 1) + " 行";

        if (result.truncated()) {
            return "\n[未读完：" + range + "，内容被大小上限截断；"
                    + "可用更大的 maxSize 重读以获取完整内容]";
        }
        if (first + result.returnedLines() - 1 >= total) {
            return "\n[已达文件末尾：共 " + total + " 行，" + range + "]";
        }
        return "\n[未读完：" + range + "，共 " + total + " 行；继续读请用 offset="
                + (first + result.returnedLines()) + "]";
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
