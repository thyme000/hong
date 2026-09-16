package cn.thyme.agent;

import cn.thyme.tool.Tool;

/**
 * Agent 工厂：根据 {@link AgentConfig} 创建 agent 实例，并可在创建时应用全局上限
 * （轮次 / token 上限回落）与创建子 agent 工具。
 */
public class AgentFactory {

    private final int maxIterationsLimit;
    private final int maxTokensLimit;

    /** 默认构造：不设上限。 */
    public AgentFactory() {
        this(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** 传入全局上限：创建 agent 时，请求值超过上限则回落为上限值。 */
    public AgentFactory(int maxIterationsLimit, int maxTokensLimit) {
        this.maxIterationsLimit = maxIterationsLimit;
        this.maxTokensLimit = maxTokensLimit;
    }

    public Agent create(AgentConfig config) {
        return new DefaultAgent(applyLimits(config));
    }

    /**
     * 把子 agent 包装成一个工具，供主 agent 委派任务。
     *
     * <p>子 agent 的预算每次委派都不同（由父级按档位划拨），所以这里传入的是
     * 一个「按预算造 agent」的工厂，而不是预先造好的实例——否则子 agent 会把
     * 父预算当成自己的上限。</p>
     */
    public Tool createSubAgentTool(String name, String description, AgentConfig subConfig,
                                   String global, int subAgentMaxIterations) {
        int iterations = Math.min(subAgentMaxIterations, maxIterationsLimit);
        int maxTokens = Math.min(subConfig.maxTokens(), maxTokensLimit);
        return new SubAgentTool(name, description, budget -> new DefaultAgent(
                AgentConfig.builder()
                        .name(subConfig.name())
                        .llmClient(subConfig.llmClient())
                        .model(subConfig.model())
                        .systemPrompt(subConfig.systemPrompt())
                        .tools(subConfig.tools())
                        .maxIterations(iterations)
                        .maxTokens(maxTokens)
                        .temperature(subConfig.temperature())
                        .eventPublisher(subConfig.eventPublisher())
                        // budget 为 null 时由 DefaultAgent 按 maxTokens 自建
                        .budget(budget)
                        .build()), global);
    }

    private AgentConfig applyLimits(AgentConfig config) {
        int maxIterations = Math.min(config.maxIterations(), maxIterationsLimit);
        int maxTokens = Math.min(config.maxTokens(), maxTokensLimit);
        if (maxIterations == config.maxIterations() && maxTokens == config.maxTokens()) {
            return config;
        }
        // 只改上限，其余字段必须原样带上：漏掉 eventPublisher 会让 agent 静默失去
        // 所有监听器（历史不再落库），漏掉 budget 会让子 agent 拿到父预算当自己的上限。
        // AgentConfig 新增字段时，这里要同步。
        return AgentConfig.builder()
                .name(config.name())
                .llmClient(config.llmClient())
                .model(config.model())
                .systemPrompt(config.systemPrompt())
                .tools(config.tools())
                .maxIterations(maxIterations)
                .maxTokens(maxTokens)
                .temperature(config.temperature())
                .eventPublisher(config.eventPublisher())
                .budget(config.budget())
                .build();
    }
}
