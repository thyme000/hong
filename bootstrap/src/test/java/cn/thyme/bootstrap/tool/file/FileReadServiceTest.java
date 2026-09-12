package cn.thyme.bootstrap.tool.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页与截断逻辑的单元测试。文件定位与访问控制已移到
 * {@code SessionWorkspace}，本类只针对纯文本输入验证。
 */
class FileReadServiceTest {

    @Test
    @DisplayName("参数全为空时返回全文")
    void readsWholeText() {
        FileReadResult result = FileReadService.read("hello\nworld\n", null, null, null);

        assertEquals("hello\nworld\n", result.content());
        assertFalse(result.truncated());
        assertEquals(2, result.totalLines());
        assertEquals(2, result.returnedLines());
    }

    @Test
    @DisplayName("超过上限时截断并标注")
    void truncatesWhenExceedsMaxChars() {
        FileReadResult result = FileReadService.read("abcdefghij", 5, null, null);

        assertEquals("abcde", result.content());
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("行范围分页从 1 开始")
    void paginatesLinesOneBased() {
        FileReadResult result = FileReadService.read("line1\nline2\nline3\n", null, 2, 1);

        assertEquals("line2\n", result.content());
        assertEquals(3, result.totalLines());
        assertEquals(1, result.returnedLines());
    }

    @Test
    @DisplayName("limit 超出剩余行数时取到结尾")
    void limitBeyondEndReadsToEnd() {
        FileReadResult result = FileReadService.read("a\nb\nc\n", null, 2, 100);

        assertEquals("b\nc\n", result.content());
        assertEquals(2, result.returnedLines());
    }

    @Test
    @DisplayName("offset 超出总行数时返回空")
    void offsetBeyondEndIsEmpty() {
        FileReadResult result = FileReadService.read("a\nb\n", null, 99, null);

        assertEquals("", result.content());
        assertEquals(0, result.returnedLines());
    }

    @Test
    @DisplayName("未传 maxChars 时使用默认上限")
    void appliesDefaultMaxChars() {
        String longText = "x".repeat(FileReadService.DEFAULT_MAX_CHARS + 100);

        FileReadResult result = FileReadService.read(longText, null, null, null);

        assertEquals(FileReadService.DEFAULT_MAX_CHARS, result.content().length());
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("中文内容按字符计数")
    void countsChineseChars() {
        FileReadResult result = FileReadService.read("中文内容\n", 2, null, null);

        assertEquals("中文", result.content());
        assertTrue(result.truncated());
    }
}
