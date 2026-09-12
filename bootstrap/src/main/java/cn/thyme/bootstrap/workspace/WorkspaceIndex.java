package cn.thyme.bootstrap.workspace;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 会话目录内元数据文件（{@code _index.json}）的读写：
 * 记录原始名到物理名的映射，使进程重启后仍能按原始名寻址。
 * 随会话目录同生共死，删除目录即彻底清理。
 */
class WorkspaceIndex {

    private static final TypeReference<List<WorkspaceFile>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper json = new ObjectMapper();

    /** 读取映射；文件不存在时返回空清单。 */
    List<WorkspaceFile> read(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(SessionWorkspace.INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        String text = Files.readString(file);
        if (text.isBlank()) {
            return List.of();
        }
        return json.readValue(text, TYPE);
    }

    /** 覆盖写入映射。 */
    void write(Path sessionDir, List<WorkspaceFile> files) throws IOException {
        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(SessionWorkspace.INDEX_FILE);
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), files);
    }
}
