package cn.thyme.agent.event;

import cn.thyme.agent.AgentResult;
import cn.thyme.llm.model.LlmRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AgentEventPublisher} 的作用域语义。
 *
 * <p>重点是嵌套：子 agent 在自己的运行里会再次 {@code begin}。若作用域只是一个
 * 会被覆盖的槽位，子运行结束后父级的监听器就再也收不到事件——父会话的历史从此
 * 不再落库，而且不报任何错。</p>
 */
class AgentEventPublisherTest {

    /** 记录收到的事件文本，便于按顺序断言。 */
    private static final class RecordingListener implements AgentListener {
        private final List<String> seen = new ArrayList<>();
        private final String tag;

        RecordingListener(String tag) {
            this.tag = tag;
        }

        @Override
        public void onEvent(AgentEvent event) {
            seen.add(tag + ":" + event.sessionId());
        }
    }

    private static AgentEvent event(String sessionId) {
        return new AgentEvent.Message(sessionId, Instant.now(), LlmRole.ASSISTANT, "x");
    }

    @Test
    void 未开始运行时事件发给全局监听器() {
        RecordingListener global = new RecordingListener("global");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(global));

        publisher.publish(event("s1"));

        assertEquals(List.of("global:s1"), global.seen);
    }

    @Test
    void 作用域监听器应屏蔽全局监听器() {
        RecordingListener global = new RecordingListener("global");
        RecordingListener scoped = new RecordingListener("scoped");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(global));

        publisher.begin(List.of(scoped));
        publisher.publish(event("s1"));
        publisher.end();

        assertEquals(List.of(), global.seen);
        assertEquals(List.of("scoped:s1"), scoped.seen);
    }

    @Test
    void 嵌套运行结束后应恢复外层作用域() {
        RecordingListener outer = new RecordingListener("outer");
        RecordingListener inner = new RecordingListener("inner");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of());

        publisher.begin(List.of(outer));
        publisher.publish(event("parent"));

        // 子 agent 运行：再次 begin，结束时应恢复父级而非清空
        publisher.begin(List.of(inner));
        publisher.publish(event("child"));
        publisher.end();

        publisher.publish(event("parent-again"));
        publisher.end();

        assertEquals(List.of("outer:parent", "outer:parent-again"), outer.seen);
        assertEquals(List.of("inner:child"), inner.seen);
    }

    @Test
    void 运行结束后应回到全局监听器() {
        RecordingListener global = new RecordingListener("global");
        RecordingListener scoped = new RecordingListener("scoped");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(global));

        publisher.begin(List.of(scoped));
        publisher.end();
        publisher.publish(event("s1"));

        assertEquals(List.of("global:s1"), global.seen);
        assertEquals(List.of(), scoped.seen);
    }

    @Test
    void 作用域传空集合应沿用全局监听器而不是静默丢弃() {
        RecordingListener global = new RecordingListener("global");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(global));

        publisher.begin(List.of());
        publisher.publish(event("s1"));
        publisher.end();

        assertEquals(List.of("global:s1"), global.seen);
    }

    @Test
    void 无参begin应使用全局监听器() {
        RecordingListener global = new RecordingListener("global");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(global));

        publisher.begin();
        publisher.publish(event("s1"));
        publisher.end();

        assertEquals(List.of("global:s1"), global.seen);
    }

    @Test
    void 多余end不应抛异常() {
        AgentEventPublisher publisher = new AgentEventPublisher(List.of());

        publisher.end();
        publisher.end();
        publisher.publish(event("s1"));
    }

    /** 防止编译期无人引用 {@link AgentResult} 导致 import 失效的护栏用例。 */
    @Test
    void 发布Finished事件应携带结果() {
        List<AgentEvent> seen = new ArrayList<>();
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(seen::add));

        publisher.begin();
        publisher.publish(new AgentEvent.Finished("s1", Instant.now(),
                AgentResult.completed("答案", 1, 10, "s1")));
        publisher.end();

        assertEquals(1, seen.size());
    }
}
