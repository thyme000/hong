package cn.thyme.bootstrap.config;

import cn.thyme.agent.AgentConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 配置：绑定 application.yml 中的 {@code llm} 段。
 * {@code llm.default-provider} 指定默认模型提供商；
 * 每个提供商可分别定义 url / models / temperature / max-tokens。
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String defaultProvider;
    private Map<String, ProviderProperties> providers = new HashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }

    public static class ProviderProperties {
        private String url;
        private List<String> models = new ArrayList<>();
        private String apikey;
        private Double temperature;
        private Integer maxTokens;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }

        public String getApikey() {
            return apikey;
        }

        public void setApikey(String apikey) {
            this.apikey = apikey;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        /**
         * 该提供商单次运行的累计 token 预算：每轮工具调用结束后检查，越线即停止发起
         * 下一轮 LLM 调用。不配置则回落到 {@link AgentConfig} 的默认上限（100000）。
         * 各提供商可分别设置。
         */
        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
