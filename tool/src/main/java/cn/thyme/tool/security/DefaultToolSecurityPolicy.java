package cn.thyme.tool.security;

import java.util.Map;
import java.util.Set;

/**
 * 默认安全策略：破坏性能力（删文件/执行进程/改状态）需确认，写入/联网允许但线程隔离，
 * 其余允许且无隔离。
 */
public class DefaultToolSecurityPolicy implements ToolSecurityPolicy {

    @Override
    public SecurityDecision evaluate(Set<Capability> capabilities, Map<String, Object> args, Object context) {
        if (capabilities.contains(Capability.FILE_DELETE)
                || capabilities.contains(Capability.PROCESS_EXEC)
                || capabilities.contains(Capability.STATE_MODIFY)) {
            return SecurityDecision.confirm(IsolationLevel.THREAD);
        }
        if (capabilities.contains(Capability.FILE_WRITE)
                || capabilities.contains(Capability.NETWORK)) {
            return SecurityDecision.allow(IsolationLevel.THREAD);
        }
        return SecurityDecision.allow(IsolationLevel.NONE);
    }
}
