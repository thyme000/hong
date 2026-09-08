package cn.thyme.llm.providers;

import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;

/**
 * Ollama 的 {@link LlmClient} 实现。Ollama 提供 OpenAI 兼容接口，
 * 本类为 {@link OpenAiCompatibleClient} 的薄包装，无 API key。
 */
public class OllamaLlmClient implements LlmClient {

    private final OpenAiCompatibleClient delegate;

    public OllamaLlmClient(String baseUrl) {
        this.delegate = new OpenAiCompatibleClient(baseUrl, null);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        return delegate.chat(request);
    }
}
