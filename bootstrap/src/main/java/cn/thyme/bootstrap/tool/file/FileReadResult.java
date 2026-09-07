package cn.thyme.bootstrap.tool.file;

/**
 * 读文件能力层的返回结果：正文、是否被截断、文件总行数与实际返回行数。
 */
public record FileReadResult(
        String content,
        boolean truncated,
        int totalLines,
        int returnedLines) {
}
