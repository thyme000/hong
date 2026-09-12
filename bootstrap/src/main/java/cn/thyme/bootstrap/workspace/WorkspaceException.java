package cn.thyme.bootstrap.workspace;

/**
 * 沙箱操作失败：文件名非法、类型不支持、超限、读写失败等。
 * 属于调用方能修正的问题，接口层应转成 4xx 而非 500。
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
