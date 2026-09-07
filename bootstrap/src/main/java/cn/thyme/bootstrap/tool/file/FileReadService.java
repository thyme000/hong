package cn.thyme.bootstrap.tool.file;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取能力层：持有根目录，把读取限制在根目录内，提供编码、行范围分页
 * 与字符数截断。不依赖 Spring，可独立测试。
 */
public class FileReadService {

    private final Path baseDir;
    private final int defaultMaxChars;

    public FileReadService(Path baseDir, int defaultMaxChars) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.defaultMaxChars = defaultMaxChars;
    }

    /**
     * 读取根目录内的一个文本文件。
     *
     * @param path    相对根目录的路径（也可传根目录内的绝对路径）
     * @param charset 编码，为空则用 UTF-8
     * @param maxChars 最大返回字符数，为空则用默认值
     * @param offset  起始行号（从 1 开始），为空则从头读
     * @param limit   读取行数，为空则读到结尾
     */
    public FileReadResult read(String path, Charset charset, Integer maxChars,
                               Integer offset, Integer limit) throws IOException {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在或不是普通文件: " + path);
        }

        Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
        List<String> lines = Files.readAllLines(file, cs);

        int from = offset == null ? 0 : Math.max(0, offset - 1);
        int to = limit == null ? lines.size() : Math.min(lines.size(), from + limit);
        if (from > lines.size()) {
            from = lines.size();
        }
        List<String> selected = lines.subList(from, to);

        StringBuilder sb = new StringBuilder();
        for (String line : selected) {
            sb.append(line).append('\n');
        }
        String content = sb.toString();

        int max = maxChars == null ? defaultMaxChars : maxChars;
        boolean truncated = content.length() > max;
        if (truncated) {
            content = content.substring(0, max);
        }

        return new FileReadResult(content, truncated, lines.size(), selected.size());
    }

    private Path resolve(String path) throws IOException {
        Path resolved = baseDir.resolve(path).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IOException("路径越界，超出根目录: " + path);
        }
        return resolved;
    }
}
