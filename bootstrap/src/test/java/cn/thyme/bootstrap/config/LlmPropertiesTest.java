package cn.thyme.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmProperties} 的绑定：每个提供商可分别定义 url / models / temperature / max-tokens，
 * 且 {@code max-tokens} 允许缺省（缺省时装配层不覆盖 {@code AgentConfig} 的默认上限）。
 */
class LlmPropertiesTest {

    /** 只加载配置绑定，不起整个 Spring 上下文。 */
    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(TestConfig.class);

    @Test
    void 每个提供商应各自绑定url与maxTokens() {
        runner.withPropertyValues(
                "llm.default-provider=ollama",
                "llm.providers.ollama.url=http://localhost:11434/v1",
                "llm.providers.ollama.models[0]=qwen3.5:9b",
                "llm.providers.ollama.temperature=0.7",
                "llm.providers.ollama.max-tokens=100000",
                "llm.providers.siliconflow.url=https://api.siliconflow.cn/v1",
                "llm.providers.siliconflow.models[0]=deepseek-ai/DeepSeek-V4-Flash",
                "llm.providers.siliconflow.temperature=0.5",
                "llm.providers.siliconflow.max-tokens=50000"
        ).run(context -> {
            LlmProperties props = context.getBean(LlmProperties.class);
            assertThat(props.getDefaultProvider()).isEqualTo("ollama");

            LlmProperties.ProviderProperties ollama = props.getProviders().get("ollama");
            assertThat(ollama.getUrl()).isEqualTo("http://localhost:11434/v1");
            assertThat(ollama.getModels()).containsExactly("qwen3.5:9b");
            assertThat(ollama.getTemperature()).isEqualTo(0.7);
            assertThat(ollama.getMaxTokens()).isEqualTo(100000);

            LlmProperties.ProviderProperties siliconflow = props.getProviders().get("siliconflow");
            assertThat(siliconflow.getUrl()).isEqualTo("https://api.siliconflow.cn/v1");
            assertThat(siliconflow.getModels()).containsExactly("deepseek-ai/DeepSeek-V4-Flash");
            assertThat(siliconflow.getTemperature()).isEqualTo(0.5);
            assertThat(siliconflow.getMaxTokens()).isEqualTo(50000);
        });
    }

    @Test
    void 未配置maxTokens时应为null以便回落到默认上限() {
        runner.withPropertyValues(
                "llm.default-provider=ollama",
                "llm.providers.ollama.url=http://localhost:11434/v1",
                "llm.providers.ollama.models[0]=qwen3.5:9b"
        ).run(context -> {
            LlmProperties props = context.getBean(LlmProperties.class);
            LlmProperties.ProviderProperties ollama = props.getProviders().get("ollama");

            assertThat(ollama.getMaxTokens()).isNull();
            assertThat(ollama.getTemperature()).isNull();
            assertThat(ollama.getApikey()).isNull();
        });
    }
}
