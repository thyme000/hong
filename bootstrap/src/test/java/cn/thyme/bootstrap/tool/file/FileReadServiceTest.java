package cn.thyme.bootstrap.tool.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadServiceTest {

    @TempDir
    Path tempDir;

    private FileReadService service(int defaultMaxChars) {
        return new FileReadService(tempDir, defaultMaxChars);
    }

    private Path write(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    private static void printResult(String label, FileReadResult result) {
        System.out.printf("  >> [%s] content=%s | truncated=%s | totalLines=%d | returnedLines=%d%n",
                label, visible(result.content()), result.truncated(),
                result.totalLines(), result.returnedLines());
    }

    private static String visible(String s) {
        return s.replace("\n", "\\n");
    }

    @Test
    @DisplayName("正常读取整个文件")
    void readsWholeFile() throws IOException {
        write("a.txt", "hello\nworld\n");
        print("读取 a.txt，参数全为空（默认 UTF-8、无上限、全文）");

        FileReadResult result = service(1000).read("a.txt", null, null, null, null);
        printResult("正常读取", result);

        assertEquals("hello\nworld\n", result.content());
        assertFalse(result.truncated());
        assertEquals(2, result.totalLines());
        assertEquals(2, result.returnedLines());
    }

    @Test
    @DisplayName("路径越界被拒绝")
    void rejectsPathTraversal() throws IOException {
        write("a.txt", "x");
        print("尝试读取 ../a.txt（越界）");

        IOException e = assertThrows(IOException.class,
                () -> service(1000).read("../a.txt", null, null, null, null));
        print("越界被拒绝: " + e.getMessage());

        assertTrue(e.getMessage().contains("越界"));
    }

    @Test
    @DisplayName("超过大小上限时截断并标注")
    void truncatesWhenExceedsMaxChars() throws IOException {
        write("a.txt", "abcdefghij");
        print("读取 a.txt，maxChars=5（原文 10 字符）");

        FileReadResult result = service(1000).read("a.txt", null, 5, null, null);
        printResult("截断", result);

        assertEquals("abcde", result.content());
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("行范围分页从 1 开始")
    void paginatesLinesOneBased() throws IOException {
        write("a.txt", "line1\nline2\nline3\n");
        print("读取 a.txt，offset=2、limit=1（只取第 2 行）");

        FileReadResult result = service(1000).read("a.txt", null, null, 2, 1);
        printResult("分页", result);

        assertEquals("line2\n", result.content());
        assertEquals(3, result.totalLines());
        assertEquals(1, result.returnedLines());
    }

    @Test
    @DisplayName("文件不存在时抛异常")
    void reportsMissingFile() {
        print("尝试读取不存在的 nope.txt");

        IOException e = assertThrows(IOException.class,
                () -> service(1000).read("nope.txt", null, null, null, null));
        print("报错: " + e.getMessage());

        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("未传 maxSize 时使用默认上限")
    void appliesDefaultMaxChars() throws IOException {
        write("a.txt", "abcdefghij");
        print("读取 a.txt，不传 maxSize，服务默认上限=3（原文 10 字符）");

        FileReadResult result = service(3).read("a.txt", null, null, null, null);
        printResult("默认上限截断", result);

        assertEquals("abc", result.content());
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("按指定编码读取中文内容")
    void readsWithCharset() throws IOException {
        write("cn.txt", "中文\n");
        print("读取 cn.txt，指定 UTF-8");

        FileReadResult result = service(1000).read("cn.txt", StandardCharsets.UTF_8, null, null, null);
        printResult("编码读取", result);

        assertEquals("中文\n", result.content());
        assertFalse(result.truncated());
    }
}
