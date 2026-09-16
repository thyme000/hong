package cn.thyme.agent.event;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Agent 事件发布器：把 agent 运行中的事件同步分发给监听器。
 * 通过 {@link #begin()} / {@link #end()} 界定一次运行，其间发出的事件
 * 分发给当时生效的监听器（默认全部）。
 *
 * <p>作用域是<b>栈</b>，不是单个槽位：子 agent 在自己的运行里会再次
 * {@code begin}，若只是覆盖，子运行结束后父级的作用域监听器就再也回不来了，
 * 于是父 agent 后续事件的订阅者全部丢失（历史不再落库）。入栈/出栈让嵌套运行
 * 天然恢复上一层。</p>
 */
public class AgentEventPublisher {

    private final List<AgentListener> listeners;

    /**
     * 每线程一个作用域栈。用栈而非单值是为了支持嵌套运行；用 ThreadLocal 而非
     * 共享字段，是因为事件在运行所在线程上同步分发。
     */
    private final ThreadLocal<Deque<List<AgentListener>>> scopes =
            ThreadLocal.withInitial(ArrayDeque::new);

    public AgentEventPublisher(List<AgentListener> listeners) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /** 无监听器的发布器（事件丢弃）。 */
    public AgentEventPublisher() {
        this(List.of());
    }

    /** 标记一次 agent 运行的开始，使用全局监听器。 */
    public void begin() {
        begin(null);
    }

    /**
     * 标记一次 agent 运行的开始，本次运行只发给 {@code scoped}。
     *
     * @param scoped 本次运行的监听器；为 null 或空表示沿用全局监听器
     *               （空集合按「未指定」处理，而非「谁都不发」——否则一次误传空集合
     *               会让整轮事件静默消失，这种失败方式极难排查）
     */
    public void begin(List<AgentListener> scoped) {
        List<AgentListener> effective =
                scoped == null || scoped.isEmpty() ? listeners : List.copyOf(scoped);
        scopes.get().push(effective);
    }

    /** 标记一次运行的结束，恢复到上一层作用域。 */
    public void end() {
        Deque<List<AgentListener>> stack = scopes.get();
        stack.poll();
        // 栈空则清掉 ThreadLocal：线程池里的线程会被复用，残留一个空栈虽无害，
        // 但 remove 能让「本线程已无进行中的运行」在线程状态上也成立
        if (stack.isEmpty()) {
            scopes.remove();
        }
    }

    /** 发出事件，同步分发给当前运行的监听器。 */
    public void publish(AgentEvent event) {
        Deque<List<AgentListener>> stack = scopes.get();
        // 栈空表示不在任何运行的作用域内，退回全局监听器
        List<AgentListener> targets = stack.isEmpty() ? listeners : stack.peek();
        for (AgentListener listener : targets) {
            listener.onEvent(event);
        }
    }
}
