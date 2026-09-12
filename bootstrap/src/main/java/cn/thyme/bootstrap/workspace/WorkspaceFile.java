package cn.thyme.bootstrap.workspace;

/**
 * 会话沙箱里的一个文件条目：对外暴露的名字是用户上传时的<b>原始名</b>，
 * 磁盘上的物理名则被改成随机串以避免重名——这个映射由元数据文件维护。
 *
 * @param name     原始文件名（用户与模型看到、用来寻址的名字）
 * @param storedAs 磁盘上的物理文件名
 * @param size     字节数
 */
public record WorkspaceFile(String name, String storedAs, long size) {
}
