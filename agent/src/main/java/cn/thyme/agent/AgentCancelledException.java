package cn.thyme.agent;

/**
 * 一次 agent 运行被调用方主动中断。
 *
 * <p>与各种「上限」不同，取消是外部意图而非资源耗尽，所以不能作为一个停止条件
 * 在循环里检查——它可能在任何一次模型调用的中途到达。约定由流式监听器在下一个
 * 增量到达时抛出，沿「监听器 → 事件发布器 → 增量回调 → 模型客户端读取循环」
 * 一路上抛，从而立刻停止等待模型。</p>
 *
 * <p>{@link DefaultAgent} 会在循环内就地把它转成
 * {@link AgentStopReason#CANCELLED} 的结果，不会让它穿到调用方——调用方需要的是
 * 「跑到第几轮、花了多少 token」，异常里带不了这些。</p>
 */
public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException(String message) {
        super(message);
    }
}
