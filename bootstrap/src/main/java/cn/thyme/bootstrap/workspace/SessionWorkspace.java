package cn.thyme.bootstrap.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会话文件沙箱：每个会话一个独立目录 {@code <root>/<sessionId>/}，会话之间互不可见。
 *
 * <p>命名策略：磁盘上的物理名是随机串（UUID + 原扩展名），因此同名文件不会互相
 * 覆盖；对外的<b>原始名</b>到物理名的映射存在会话目录内的 {@code _index.json} 里，
 * 进程重启后依然可解析。用户与模型全程使用原始名寻址。</p>
 *
 * <p>目录惰性创建：建会话时只生成 id，首次上传才落盘，避免反复「新建对话」
 * 堆出大量空目录。</p>
 */
@Component
public class SessionWorkspace {

    /** 元数据文件，记录原始名到物理名的映射。 */
    static final String INDEX_FILE = "_index.json";

    /** 本期只接受文本类文件；后续要支持二进制时在此扩展或改为内容探测。 */
    static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "json", "csv", "xml", "yaml", "yml", "log", "html", "htm",
            "css", "js", "ts", "java", "py", "go", "rs", "sql", "sh", "properties");

    /** 单文件大小上限。 */
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final Path root;
    private final WorkspaceIndex index;

    public SessionWorkspace(@Value("${tool.workspace.dir:./data/workspaces}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        this.index = new WorkspaceIndex();
    }

    /** 会话目录（可能尚未创建）。 */
    public Path sessionDir(String sessionId) {
        Path dir = root.resolve(sanitizeSessionId(sessionId)).normalize();
        // 白名单已挡住分隔符与 ..，此处兜底防止将来白名单被放宽后越界
        if (!dir.startsWith(root)) {
            throw new WorkspaceException("会话标识越界: " + sessionId);
        }
        return dir;
    }

    /**
     * 保存一个上传文件，返回落盘后的条目。
     * 原始名重复时直接覆盖旧文件（符合「重传新版本」的直觉）。
     */
    public WorkspaceFile save(String sessionId, String originalName, byte[] content) {
        String name = sanitizeName(originalName);
        validateExtension(name);
        if (content == null) {
            throw new WorkspaceException("文件内容为空: " + name);
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new WorkspaceException("文件超过大小上限 "
                    + MAX_FILE_SIZE / 1024 / 1024 + "MB: " + name);
        }

        Path dir = sessionDir(sessionId);
        try {
            Files.createDirectories(dir);
            List<WorkspaceFile> files = new ArrayList<>(index.read(dir));

            // 同名覆盖：沿用既有的物理名，避免留下孤儿文件
            WorkspaceFile existing = findByName(files, name);
            String storedAs = existing == null ? newStoredName(name) : existing.storedAs();
            if (existing != null) {
                files.remove(existing);
            }

            Files.write(dir.resolve(storedAs), content);
            WorkspaceFile saved = new WorkspaceFile(name, storedAs, content.length);
            files.add(saved);
            index.write(dir, files);
            return saved;
        } catch (IOException e) {
            throw new WorkspaceException("保存文件失败: " + name, e);
        }
    }

    /** 会话内的文件清单，按原始名排序。 */
    public List<WorkspaceFile> list(String sessionId) {
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try {
            return index.read(dir).stream()
                    .sorted(Comparator.comparing(WorkspaceFile::name))
                    .toList();
        } catch (IOException e) {
            throw new WorkspaceException("读取文件清单失败: " + sessionId, e);
        }
    }

    /**
     * 按原始名定位文件。带路径分隔符的名字会被拒绝——沙箱是平铺的，
     * 不接受子目录，也就堵死了 {@code ../} 之类的越界尝试。
     */
    public Path resolve(String sessionId, String name) {
        String clean = sanitizeName(name);
        Path file = sessionDir(sessionId).resolve(clean).normalize();
        if (!file.startsWith(sessionDir(sessionId))) {
            throw new WorkspaceException("路径越界: " + name);
        }
        return file;
    }

    /** 读取会话内某个文件的文本内容（UTF-8）。 */
    public String readText(String sessionId, String name) {
        WorkspaceFile file = findByName(list(sessionId), sanitizeName(name));
        if (file == null) {
            throw new WorkspaceException("文件不存在: " + name);
        }
        try {
            return Files.readString(sessionDir(sessionId).resolve(file.storedAs()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkspaceException("读取文件失败: " + name, e);
        }
    }

    /** 删除会话内某个文件，返回是否真的删除了。 */
    public boolean delete(String sessionId, String name) {
        String clean = sanitizeName(name);
        Path dir = sessionDir(sessionId);
        try {
            List<WorkspaceFile> files = new ArrayList<>(index.read(dir));
            WorkspaceFile file = findByName(files, clean);
            if (file == null) {
                return false;
            }
            Files.deleteIfExists(dir.resolve(file.storedAs()));
            files.remove(file);
            index.write(dir, files);
            return true;
        } catch (IOException e) {
            throw new WorkspaceException("删除文件失败: " + clean, e);
        }
    }

    /** 删除整个会话目录。 */
    public void deleteSession(String sessionId) {
        Path dir = sessionDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new WorkspaceException("删除会话目录失败: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException("删除会话目录失败: " + sessionId, e);
        }
    }

    private static WorkspaceFile findByName(List<WorkspaceFile> files, String name) {
        return files.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /** 物理名：UUID 去掉横线 + 原扩展名，保证不与任何既有文件重名。 */
    private static String newStoredName(String originalName) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        int dot = originalName.lastIndexOf('.');
        return dot < 0 ? uuid : uuid + originalName.substring(dot);
    }

    /** 只取文件名部分，拒绝任何目录成分。 */
    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new WorkspaceException("文件名不能为空");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new WorkspaceException("文件名不能包含路径分隔符: " + name);
        }
        // 元数据文件名保留给内部使用，不允许用户以同名上传（纵深防御）
        if (INDEX_FILE.equals(name)) {
            throw new WorkspaceException("文件名保留: " + name);
        }
        return name;
    }

    private static void validateExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!TEXT_EXTENSIONS.contains(ext)) {
            throw new WorkspaceException("不支持的文件类型: " + name
                    + "（仅支持文本类文件：" + String.join(", ", new java.util.TreeSet<>(TEXT_EXTENSIONS)) + "）");
        }
    }

    /**
     * 会话标识校验：只允许字母、数字、下划线、连字符。
     * 会话 id 会被当作沙箱目录名使用，必须堵死路径分隔符与 {@code ..}，
     * 否则携带 {@code ../../} 的 sessionId 就能越出沙箱。
     */
    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new WorkspaceException("缺少会话标识，无法定位沙箱目录");
        }
        if (!sessionId.matches("[A-Za-z0-9_-]+")) {
            throw new WorkspaceException("会话标识含非法字符: " + sessionId);
        }
        return sessionId;
    }
}
