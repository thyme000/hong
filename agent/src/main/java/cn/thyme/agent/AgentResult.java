package cn.thyme.agent;

import java.util.Objects;

/**
 * Agent 执行结果：最终文本、实际轮次、累计 token、是否因上限被截断。
 */
public record AgentResult(String content, int iterations, int totalTokens, boolean truncated) {

    public AgentResult {
        Objects.requireNonNull(content, "content must not be null");
    }
}
