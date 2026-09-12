package cn.thyme.agent;

import java.util.Objects;

/**
 * Agent 执行结果：最终文本、实际轮次、累计 token、是否因上限被截断，
 * 以及本次运行所属的会话 id（回传调用方，用于把后续请求接回同一会话）。
 */
public record AgentResult(String content, int iterations, int totalTokens, boolean truncated, String sessionId) {

    public AgentResult {
        Objects.requireNonNull(content, "content must not be null");
    }
}
