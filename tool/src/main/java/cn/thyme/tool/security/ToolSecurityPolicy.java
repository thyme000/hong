package cn.thyme.tool.security;

import java.util.Map;
import java.util.Set;

/**
 * 工具安全策略：根据工具能力 + 参数 + 环境，动态评估风险并给出决策。
 * 可插拔，默认实现见 {@link DefaultToolSecurityPolicy}。
 */
public interface ToolSecurityPolicy {

    /**
     * 评估一次工具调用的安全决策。
     *
     * @param capabilities 工具声明的能力
     * @param args         本次调用的参数
     * @param context      执行环境（预留，第一版可为 null）
     */
    SecurityDecision evaluate(Set<Capability> capabilities, Map<String, Object> args, Object context);
}
