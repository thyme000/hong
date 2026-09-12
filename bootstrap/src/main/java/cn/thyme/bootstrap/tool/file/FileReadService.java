package cn.thyme.bootstrap.tool.file;

import java.util.List;

/**
 * 读取结果的分页与截断：入参已是文件全文，此处只负责按行范围选取与字符数截断。
 * 文件定位与访问控制由 {@code SessionWorkspace} 承担，故本类不再接触文件系统。
 */
final class FileReadService {

    private FileReadService() {
    }

    /**
     * 对全文做分页与截断。
     *
     * @param text     文件全文
     * @param maxChars 最大返回字符数，为空则用默认值
     * @param offset   起始行号（从 1 开始），为空则从头读
     * @param limit    读取行数，为空则读到结尾
     */
    static FileReadResult read(String text, Integer maxChars, Integer offset, Integer limit) {
        List<String> lines = text.lines().toList();

        int from = offset == null ? 0 : Math.max(0, offset - 1);
        if (from > lines.size()) {
            from = lines.size();
        }
        int to = limit == null ? lines.size() : Math.min(lines.size(), from + limit);
        List<String> selected = lines.subList(from, to);

        StringBuilder sb = new StringBuilder();
        for (String line : selected) {
            sb.append(line).append('\n');
        }
        String content = sb.toString();

        int max = maxChars == null ? FileReadService.DEFAULT_MAX_CHARS : maxChars;
        boolean truncated = content.length() > max;
        if (truncated) {
            content = content.substring(0, max);
        }

        return new FileReadResult(content, truncated, lines.size(), selected.size());
    }

    static final int DEFAULT_MAX_CHARS = 10000;
}
