package cn.thyme.bootstrap.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话沙箱的核心行为：会话隔离、原始名寻址、物理名随机化、
 * 路径越界与类型/大小校验、删除语义、重启后映射可恢复。
 */
class SessionWorkspaceTest {

    @TempDir
    Path root;

    private static final String SESSION = "s-1";

    private SessionWorkspace workspace() {
        return new SessionWorkspace(root.toString());
    }

    @Test
    @DisplayName("上传后可按原始名读回内容")
    void savesAndReadsByOriginalName() {
        SessionWorkspace ws = workspace();
        ws.save(SESSION, "a.txt", "十万个冷笑话".getBytes(StandardCharsets.UTF_8));

        assertEquals("十万个冷笑话", ws.readText(SESSION, "a.txt"));
    }

    @Test
    @DisplayName("磁盘物理名已随机化，与原始名不同")
    void storesUnderRandomPhysicalName() {
        SessionWorkspace ws = workspace();
        ws.save(SESSION, "a.txt", "hi".getBytes(StandardCharsets.UTF_8));

        WorkspaceFile file = ws.list(SESSION).get(0);
        assertEquals("a.txt", file.name());
        assertNotEquals("a.txt", file.storedAs());
        assertTrue(file.storedAs().endsWith(".txt"), "应保留扩展名: " + file.storedAs());
        assertTrue(Files.exists(root.resolve(SESSION).resolve(file.storedAs())));
    }

    @Test
    @DisplayName("同名文件覆盖：沿用同一物理名，不产生孤儿文件")
    void overwritesSameNameInPlace() {
        SessionWorkspace ws = workspace();
        ws.save(SESSION, "a.txt", "first".getBytes(StandardCharsets.UTF_8));
        String storedAs = ws.list(SESSION).get(0).storedAs();
        ws.save(SESSION, "a.txt", "second".getBytes(StandardCharsets.UTF_8));

        List<WorkspaceFile> files = ws.list(SESSION);
        assertEquals(1, files.size());
        assertEquals(storedAs, files.get(0).storedAs());
        assertEquals("second", ws.readText(SESSION, "a.txt"));
    }

    @Test
    @DisplayName("不同会话互相看不到对方的文件")
    void isolatesBetweenSessions() {
        SessionWorkspace ws = workspace();
        ws.save("s-1", "a.txt", "one".getBytes(StandardCharsets.UTF_8));
        ws.save("s-2", "b.txt", "two".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("a.txt"), ws.list("s-1").stream().map(WorkspaceFile::name).toList());
        assertEquals(List.of("b.txt"), ws.list("s-2").stream().map(WorkspaceFile::name).toList());
        assertThrows(WorkspaceException.class, () -> ws.readText("s-2", "a.txt"));
    }

    @Test
    @DisplayName("拒绝带路径分隔符的文件名（越界防护）")
    void rejectsPathTraversal() {
        SessionWorkspace ws = workspace();

        assertThrows(WorkspaceException.class,
                () -> ws.save(SESSION, "../evil.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(WorkspaceException.class,
                () -> ws.save(SESSION, "a/../../evil.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(WorkspaceException.class,
                () -> ws.resolve(SESSION, "../../etc/passwd"));
        assertThrows(WorkspaceException.class, () -> ws.readText(SESSION, "../a.txt"));
    }

    @Test
    @DisplayName("拒绝白名单外的扩展名")
    void rejectsNonTextExtensions() {
        SessionWorkspace ws = workspace();

        WorkspaceException e = assertThrows(WorkspaceException.class,
                () -> ws.save(SESSION, "evil.exe", "x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("不支持的文件类型"));
        assertThrows(WorkspaceException.class,
                () -> ws.save(SESSION, "noext", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("超过大小上限时拒绝")
    void rejectsOversizedFile() {
        SessionWorkspace ws = workspace();
        byte[] tooBig = new byte[(int) SessionWorkspace.MAX_FILE_SIZE + 1];

        WorkspaceException e = assertThrows(WorkspaceException.class,
                () -> ws.save(SESSION, "big.txt", tooBig));
        assertTrue(e.getMessage().contains("超过大小上限"));
    }

    @Test
    @DisplayName("读取不存在的文件报错")
    void reportsMissingFile() {
        SessionWorkspace ws = workspace();

        WorkspaceException e = assertThrows(WorkspaceException.class,
                () -> ws.readText(SESSION, "nope.txt"));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("没上传过文件的会话清单为空，且不创建目录")
    void emptySessionHasNoDirectory() {
        SessionWorkspace ws = workspace();

        assertTrue(ws.list(SESSION).isEmpty());
        assertFalse(Files.exists(root.resolve(SESSION)), "惰性创建：未上传不应建目录");
    }

    @Test
    @DisplayName("删除单个文件后清单与磁盘都清理干净")
    void deletesSingleFile() {
        SessionWorkspace ws = workspace();
        ws.save(SESSION, "a.txt", "one".getBytes(StandardCharsets.UTF_8));
        ws.save(SESSION, "b.txt", "two".getBytes(StandardCharsets.UTF_8));
        String storedA = ws.list(SESSION).stream()
                .filter(f -> f.name().equals("a.txt")).findFirst().orElseThrow().storedAs();

        assertTrue(ws.delete(SESSION, "a.txt"));
        assertEquals(List.of("b.txt"), ws.list(SESSION).stream().map(WorkspaceFile::name).toList());
        assertFalse(Files.exists(root.resolve(SESSION).resolve(storedA)));
        assertFalse(ws.delete(SESSION, "a.txt"), "重复删除应返回 false");
    }

    @Test
    @DisplayName("删除会话会清空整个目录")
    void deletesWholeSession() {
        SessionWorkspace ws = workspace();
        ws.save(SESSION, "a.txt", "one".getBytes(StandardCharsets.UTF_8));
        ws.save(SESSION, "b.txt", "two".getBytes(StandardCharsets.UTF_8));

        ws.deleteSession(SESSION);

        assertFalse(Files.exists(root.resolve(SESSION)));
        assertTrue(ws.list(SESSION).isEmpty());
    }

    @Test
    @DisplayName("重启后（新实例）仍能按原始名读回文件")
    void survivesRestartViaIndexFile() {
        workspace().save(SESSION, "a.txt", "persisted".getBytes(StandardCharsets.UTF_8));

        // 新实例模拟进程重启：内存里没有任何映射，只能靠 _index.json
        SessionWorkspace afterRestart = workspace();

        assertEquals("persisted", afterRestart.readText(SESSION, "a.txt"));
        assertEquals(List.of("a.txt"), afterRestart.list(SESSION).stream().map(WorkspaceFile::name).toList());
    }

    @Test
    @DisplayName("缺少 sessionId 时报错而非落到根目录")
    void rejectsMissingSessionId() {
        SessionWorkspace ws = workspace();

        assertThrows(WorkspaceException.class, () -> ws.list(null));
        assertThrows(WorkspaceException.class, () -> ws.list(""));
        assertThrows(WorkspaceException.class, () -> ws.save("", "a.txt", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("sessionId 含路径遍历时被拒绝，不会越出沙箱根目录")
    void rejectsPathTraversalInSessionId() {
        SessionWorkspace ws = workspace();
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        for (String evil : List.of("../evil", "../../evil", "..", "a/../../evil", "a/b", "..\\evil")) {
            assertThrows(WorkspaceException.class, () -> ws.sessionDir(evil), "应拒绝 sessionDir: " + evil);
            assertThrows(WorkspaceException.class, () -> ws.list(evil), "应拒绝 list: " + evil);
            assertThrows(WorkspaceException.class, () -> ws.save(evil, "a.txt", content), "应拒绝 save: " + evil);
            assertThrows(WorkspaceException.class, () -> ws.deleteSession(evil), "应拒绝 deleteSession: " + evil);
        }

        // 沙箱根目录之外不应出现任何越界产物
        assertFalse(Files.exists(root.getParent().resolve("evil")));
    }

    @Test
    @DisplayName("合法的 sessionId（字母数字下划线连字符）正常可用")
    void acceptsSafeSessionIds() {
        SessionWorkspace ws = workspace();

        ws.save("Abc-123_xyz", "a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", ws.readText("Abc-123_xyz", "a.txt"));
    }

    @Test
    @DisplayName("保留的元数据文件名 _index.json 不允许用户上传")
    void rejectsReservedIndexFileName() {
        SessionWorkspace ws = workspace();
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);

        assertThrows(WorkspaceException.class, () -> ws.save(SESSION, "_index.json", content));
    }
}
