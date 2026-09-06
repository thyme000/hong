package cn.thyme.bootstrap.llm;

import cn.thyme.bootstrap.llm.model.LlmRequest;
import cn.thyme.bootstrap.llm.model.LlmResponse;

/**
 * 厂商无关的 LLM 调用入口。实现类负责把 {@link LlmRequest} 翻译成各自厂商的协议；
 * 调用方只依赖本接口与模型类型，从不接触任何厂商 SDK。
 */
public interface LlmClient {

    /**
     * 发送一次对话请求并返回助手的回复文本。
     */
    LlmResponse chat(LlmRequest request);
}
