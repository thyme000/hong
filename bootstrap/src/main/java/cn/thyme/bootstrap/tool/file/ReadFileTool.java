package cn.thyme.bootstrap.tool.file;

import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolMark;
import cn.thyme.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读文件工具：读取根目录内指定文件的内容，支持编码、行范围分页与字符数截断。
 * 根目录由配置项 {@code tool.read.base-dir} 指定，未配置时本工具不注册。
 */
@Component
@ConditionalOnProperty(name = "tool.read.base-dir")
@ToolMark(category = "file", action = "read")
public class ReadFileTool implements Tool {

    private static final int DEFAULT_MAX_CHARS = 10000;

    private final FileReadService service;

    public ReadFileTool(@Value("${tool.read.base-dir}") String baseDir) {
        this.service = new FileReadService(Paths.get(baseDir), DEFAULT_MAX_CHARS);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取根目录内指定文件的内容，支持指定编码、按行范围分页与按字符数截断。";
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SYNC_ONLY;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of("type", "string", "description", "文件路径（相对根目录）"));
        properties.put("encoding", Map.of("type", "string", "description", "编码，默认 UTF-8"));
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
    public ToolResult execute(Map<String, Object> args) {
        Object path = args.get("path");
        if (path == null) {
            return ToolResult.failure("缺少必填参数: path");
        }

        try {
            Charset charset = toCharset(args.get("encoding"));
            FileReadResult result = service.read(
                    String.valueOf(path),
                    charset,
                    toInt(args.get("maxSize")),
                    toInt(args.get("offset")),
                    toInt(args.get("limit")));

            String text = result.content();
            if (result.truncated()) {
                text += "\n[已截断：内容超过大小上限]";
            }
            return ToolResult.success(text, result);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private static Charset toCharset(Object value) {
        if (value == null) {
            return null;
        }
        return Charset.forName(String.valueOf(value));
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
