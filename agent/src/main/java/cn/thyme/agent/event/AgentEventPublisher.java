package cn.thyme.agent.event;

import java.util.List;

/**
 * Agent 事件发布器：把 agent 运行中的事件同步分发给监听器。
 * 通过 {@link #begin()} / {@link #end()} 界定一次运行，其间发出的事件
 * 分发给当时生效的监听器（默认全部）。
 */
public class AgentEventPublisher {

    private final List<AgentListener> listeners;
    private final ThreadLocal<List<AgentListener>> active = new ThreadLocal<>();

    public AgentEventPublisher(List<AgentListener> listeners) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /** 无监听器的发布器（事件丢弃）。 */
    public AgentEventPublisher() {
        this(List.of());
    }

    /** 标记一次 agent 运行的开始。 */
    public void begin() {
        active.set(listeners);
    }

    /** 标记一次 agent 运行的结束。 */
    public void end() {
        active.remove();
    }

    /** 发出事件，同步分发给当前运行的监听器。 */
    public void publish(AgentEvent event) {
        List<AgentListener> targets = active.get();
        if (targets == null) {
            targets = listeners;
        }
        for (AgentListener listener : targets) {
            listener.onEvent(event);
        }
    }
}
