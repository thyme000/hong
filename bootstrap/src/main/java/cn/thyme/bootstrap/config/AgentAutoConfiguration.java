package cn.thyme.bootstrap.config;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentConfig;
import cn.thyme.agent.AgentFactory;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.providers.OllamaLlmClient;
import cn.thyme.llm.providers.SiliconFlowLlmClient;
import cn.thyme.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 主 agent 装配：读 {@link LlmProperties} 的默认提供商创建 {@link LlmClient}
 * （带健康检查），并创建主 agent（hong）。事件发布器分发给容器内所有 {@link AgentListener}。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class AgentAutoConfiguration {

    @Value("${agent.system-prompt-file:prompts/general.md}")
    private String systemPromptFile;

    @Bean
    public AgentEventPublisher agentEventPublisher(List<AgentListener> listeners) {
        return new AgentEventPublisher(listeners);
    }

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        String provider = props.getDefaultProvider();
        LlmProperties.ProviderProperties p = props.getProviders().get(provider);
        if (p == null) {
            throw new IllegalStateException("未配置模型提供商: " + provider);
        }
        if (p.getModels() == null || p.getModels().isEmpty()) {
            throw new IllegalStateException("提供商未配置模型: " + provider);
        }
        LlmClient client = createClient(provider, p);
        healthCheck(client, p.getModels().get(0));
        return client;
    }

    @Bean
    public Agent mainAgent(LlmClient llmClient, LlmProperties props, List<Tool> tools,
                           AgentEventPublisher eventPublisher) {
        String provider = props.getDefaultProvider();
        LlmProperties.ProviderProperties p = props.getProviders().get(provider);
        String model = p.getModels().get(0);

        AgentConfig.Builder builder = AgentConfig.builder()
                .name("hong")
                .llmClient(llmClient)
                .model(model)
                .systemPromptClasspath(systemPromptFile)
                .tools(tools)
                .temperature(p.getTemperature())
                .eventPublisher(eventPublisher);
        // 未配置时保持 AgentConfig 的默认上限，不在这里补一个平行的默认值
        if (p.getMaxTokens() != null) {
            builder.maxTokens(p.getMaxTokens());
        }
        return new AgentFactory().create(builder.build());
    }

    private LlmClient createClient(String provider, LlmProperties.ProviderProperties p) {
        return switch (provider) {
            case "ollama" -> new OllamaLlmClient(p.getUrl());
            case "siliconflow" -> new SiliconFlowLlmClient(p.getUrl(), p.getApikey());
            default -> throw new IllegalStateException("不支持的模型提供商: " + provider);
        };
    }

    private void healthCheck(LlmClient client, String model) {
        for (int i = 1; i <= 3; i++) {
            try {
                client.chat(LlmRequest.builder().model(model).addUserMessage("ping").build());
                return;
            } catch (Exception e) {
                if (i == 3) {
                    throw new IllegalStateException("模型健康检查失败（重试 3 次）: " + model, e);
                }
            }
        }
    }
}
