package cn.thyme.llm;

import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;

import java.util.function.Consumer;

/**
 * 厂商无关的 LLM 调用入口。实现类负责把 {@link LlmRequest} 翻译成各自厂商的协议；
 * 调用方只依赖本接口与模型类型，从不接触任何厂商 SDK。
 *
 * <p>接口只有一条流式路径：{@link #chat(LlmRequest, Consumer)} 是唯一的抽象方法，
 * 它在返回完整结果的同时把产出过程增量回调出去。不想要增量的调用方用
 * {@link #chat(LlmRequest)}，它只是传了个空回调——这样厂商适配器不必同时维护
 * 「同步」与「流式」两套解析逻辑，两者的行为也不可能不一致。</p>
 */
public interface LlmClient {

    /** 空增量回调：不关心产出过程的调用方复用同一实例，避免每次调用都分配。 */
    Consumer<LlmDelta> IGNORE_DELTAS = delta -> {
    };

    /**
     * 发送一次对话请求，并把模型产出的增量实时回调给 {@code deltaListener}。
     *
     * @param deltaListener 增量回调，在调用线程上同步执行。回调抛出的异常会中断本次
     *                      调用并原样向上传播——调用方据此实现「中途取消」
     * @return 完整响应（文本、工具调用、用量），与增量回调拼接的结果一致
     */
    LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener);

    /** 发送一次对话请求并返回助手的回复文本，不接收产出过程。 */
    default LlmResponse chat(LlmRequest request) {
        return chat(request, IGNORE_DELTAS);
    }
}
