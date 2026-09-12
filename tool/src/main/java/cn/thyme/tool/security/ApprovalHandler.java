package cn.thyme.tool.security;

import java.util.Map;

/**
 * 用户确认处理器：当安全策略决定 {@link SecurityAction#CONFIRM} 时，
 * 由执行层调用本接口请求用户确认（同步阻塞等待）。
 */
public interface ApprovalHandler {

    /**
     * 请求用户确认是否允许执行。
     *
     * @param toolName 工具名
     * @param args     本次调用参数
     * @return true 表示允许，false 表示拒绝
     */
    boolean requestApproval(String toolName, Map<String, Object> args);
}
