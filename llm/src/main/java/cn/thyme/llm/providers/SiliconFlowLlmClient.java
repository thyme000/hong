package cn.thyme.llm.providers;

import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;

import java.util.function.Consumer;

/**
 * 硅基流动（SiliconFlow）的 {@link LlmClient} 实现。提供 OpenAI 兼容接口，
 * 本类为 {@link OpenAiCompatibleClient} 的薄包装，需 API key。
 */
public class SiliconFlowLlmClient implements LlmClient {

    private final OpenAiCompatibleClient delegate;

    public SiliconFlowLlmClient(String baseUrl, String apiKey) {
        this.delegate = new OpenAiCompatibleClient(baseUrl, apiKey);
    }

    /** 必须显式覆写：漏了会静默走默认方法，流式永不生效且不报错。 */
    @Override
    public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
        return delegate.chat(request, deltaListener);
    }
}
