package cn.thyme.agent.event;

/**
 * Agent 事件监听器：接收 agent 运行中发出的事件。可以有多个实现，
 * 分别负责会话历史持久化、日志收集、经验沉淀等。
 */
@FunctionalInterface
public interface AgentListener {

    void onEvent(AgentEvent event);
}
