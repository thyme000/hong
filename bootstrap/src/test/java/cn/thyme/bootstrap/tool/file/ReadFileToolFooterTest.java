package cn.thyme.bootstrap.tool.file;

import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读文件工具的行范围页脚：模型靠它判断「是否已读完」。
 * 缺少该信号时模型会反复重读同一文件。
 */
class ReadFileToolFooterTest {

    @TempDir
    Path root;

    private static final String SESSION = "s-footer";

    private ToolResult read(String fileName, Map<String, Object> extra) {
        SessionWorkspace workspace = new SessionWorkspace(root.toString());
        Tool tool = new ReadFileTool(workspace);
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>(extra);
        args.put("path", fileName);
        return tool.execute(args, new ToolContext(SESSION));
    }

    private void upload(String name, String content) {
        new SessionWorkspace(root.toString())
                .save(SESSION, name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("一次读完时页脚标明已达末尾")
    void marksEndOfFile() {
        upload("a.txt", "line1\nline2\nline3\n");

        ToolResult result = read("a.txt", Map.of());

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("已达文件末尾"), "应标明已读完");
        assertTrue(result.text().contains("共 3 行"));
    }

    @Test
    @DisplayName("分页未读完时页脚给出下一段 offset")
    void marksPartialReadWithNextOffset() {
        upload("a.txt", "l1\nl2\nl3\nl4\nl5\n");

        ToolResult result = read("a.txt", Map.of("offset", 1, "limit", 2));

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("未读完"), "应标明还没读完");
        assertTrue(result.text().contains("第 1-2 行"));
        assertTrue(result.text().contains("共 5 行"));
        assertTrue(result.text().contains("offset=3"), "应给出下一段起始行");
    }

    @Test
    @DisplayName("读到最后一页时页脚标明已达末尾")
    void marksLastPageAsEnd() {
        upload("a.txt", "l1\nl2\nl3\nl4\nl5\n");

        ToolResult result = read("a.txt", Map.of("offset", 4, "limit", 10));

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("已达文件末尾"));
        assertTrue(result.text().contains("第 4-5 行"));
    }

    @Test
    @DisplayName("offset 超出末尾时页脚说明越界")
    void marksOffsetBeyondEnd() {
        upload("a.txt", "l1\nl2\n");

        ToolResult result = read("a.txt", Map.of("offset", 99));

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("超出文件末尾"));
        assertTrue(result.text().contains("共 2 行"));
    }

    @Test
    @DisplayName("空文件标明文件为空")
    void marksEmptyFile() {
        upload("empty.txt", "");

        ToolResult result = read("empty.txt", Map.of());

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("文件为空"));
    }

    @Test
    @DisplayName("被大小上限截断时引导用更大的 maxSize，而非错误的 offset")
    void marksTruncated() {
        upload("big.txt", "x".repeat(200));

        ToolResult result = read("big.txt", Map.of("maxSize", 10));

        System.out.println(">> " + result.text().replace("\n", "\\n"));
        assertTrue(result.text().contains("未读完"));
        assertTrue(result.text().contains("大小上限截断"), "应说明是截断而非后面还有内容");
        assertTrue(result.text().contains("maxSize"), "截断应引导用 maxSize 重读");
        assertFalse(result.text().contains("offset="), "截断场景不应给出 offset 续读指引（行已读完）");
    }
}
