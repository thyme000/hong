package cn.thyme.agent;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主动取消：监听器在增量到达时抛出 {@link AgentCancelledException}，
 * 一路穿过模型客户端的读取循环，最终由 {@link DefaultAgent} 就地转成
 * {@link AgentStopReason#CANCELLED} 的结果。
 *
 * <p>取消的关键是<b>半截文本必须丢弃</b>：否则下次回注历史时，残句会被
 * 当成一轮正常回答重新喂给模型。</p>
 */
class DefaultAgentCancelTest {

    /** 每片增量都回调一次，模拟模型持续产出；被取消时回调会抛异常。 */
    private static final class ChattyClient implements LlmClient {

        private final List<String> pieces;
        /** 已推给回调的片数（含触发取消的那一片）。 */
        private int delivered;

        ChattyClient(List<String> pieces) {
            this.pieces = pieces;
        }

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            for (String piece : pieces) {
                delivered++;
                deltaListener.accept(LlmDelta.content(piece));
            }
            return new LlmResponse("test-model", String.join("", pieces), null, null);
        }
    }

    @Test
    void 取消后的结果应标记为CANCELLED且没有最终内容() {
        ChattyClient client = new ChattyClient(List.of("一", "二", "三", "四"));
        // 收到第二片就取消
        AgentListener cancelling = new AgentListener() {
            private int seen;

            @Override
            public void onEvent(AgentEvent event) {
                if (event instanceof AgentEvent.Delta && ++seen == 2) {
                    throw new AgentCancelledException("用户点了停止");
                }
            }
        };

        AgentResult result = run(client, cancelling);

        assertEquals(AgentStopReason.CANCELLED, result.stopReason());
        assertNull(result.content(), "半截文本必须丢弃");
        assertTrue(result.truncated());
        assertEquals(2, client.delivered, "取消应在增量回调处立刻生效，不再继续产出");
    }

    @Test
    void 取消后不应发出assistant的Message即半截文本不进历史() {
        ChattyClient client = new ChattyClient(List.of("一", "二", "三"));
        List<AgentEvent> events = new ArrayList<>();
        AgentListener cancelling = event -> {
            events.add(event);
            if (event instanceof AgentEvent.Delta) {
                throw new AgentCancelledException("取消");
            }
        };

        run(client, cancelling);

        List<AgentEvent.Message> assistantMessages = events.stream()
                .filter(AgentEvent.Message.class::isInstance)
                .map(AgentEvent.Message.class::cast)
                .filter(m -> m.role() == LlmRole.ASSISTANT)
                .toList();
        assertTrue(assistantMessages.isEmpty(),
                "取消发生在发布 Message 之前，因此不该有任何 assistant 消息进入历史");
    }

    @Test
    void 取消应回报已发生的轮次与用量() {
        ChattyClient client = new ChattyClient(List.of("唯", "一"));
        AgentListener cancelling = event -> {
            if (event instanceof AgentEvent.Delta) {
                throw new AgentCancelledException("取消");
            }
        };

        AgentResult result = run(client, cancelling);

        assertEquals(1, result.iterations(), "已经开始的这一轮应被计入");
        assertTrue(result.totalTokens() >= 0);
    }

    @Test
    void 取消后应发出Finished事件让订阅者收尾() {
        ChattyClient client = new ChattyClient(List.of("一"));
        List<AgentEvent> events = new ArrayList<>();
        AgentListener cancelling = event -> {
            events.add(event);
            if (event instanceof AgentEvent.Delta) {
                throw new AgentCancelledException("取消");
            }
        };

        run(client, cancelling);

        List<AgentEvent.Finished> finished = events.stream()
                .filter(AgentEvent.Finished.class::isInstance)
                .map(AgentEvent.Finished.class::cast)
                .toList();
        assertEquals(1, finished.size(), "即便被取消，也必须有一次收尾事件");
        assertEquals(AgentStopReason.CANCELLED, finished.get(0).result().stopReason());
    }

    @Test
    void 未取消时应正常完成() {
        ChattyClient client = new ChattyClient(List.of("一", "二"));

        AgentResult result = run(client, event -> {
        });

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals("一二", result.content());
    }

    @Test
    void 取消标志应不受监听器被多次调用影响() {
        ChattyClient client = new ChattyClient(List.of("a", "b", "c", "d", "e"));
        // 每次增量都抛：确保异常能反复打断，而不是只对第一次有效
        AgentListener alwaysCancel = event -> {
            if (event instanceof AgentEvent.Delta) {
                throw new AgentCancelledException("取消");
            }
        };

        AgentResult result = run(client, alwaysCancel);

        assertEquals(AgentStopReason.CANCELLED, result.stopReason());
        assertEquals(1, client.delivered);
    }

    private static AgentResult run(LlmClient client, AgentListener listener) {
        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(client)
                .model("test-model")
                .systemPrompt("")
                .maxIterations(5)
                .eventPublisher(new AgentEventPublisher(List.of(listener)))
                .build();
        return new DefaultAgent(config).run(
                AgentContext.builder().task("说点什么").sessionId("cancel-test").build());
    }

    /** 确认取消路径不会把用户消息也吞掉（用户消息在取消前就已发出）。 */
    @Test
    void 取消前已发出的用户消息应保留() {
        ChattyClient client = new ChattyClient(List.of("一"));
        List<AgentEvent> events = new ArrayList<>();
        AgentListener cancelling = event -> {
            events.add(event);
            if (event instanceof AgentEvent.Delta) {
                throw new AgentCancelledException("取消");
            }
        };

        run(client, cancelling);

        assertTrue(events.stream()
                        .filter(AgentEvent.Message.class::isInstance)
                        .map(AgentEvent.Message.class::cast)
                        .anyMatch(m -> m.role() == LlmRole.USER),
                "用户消息在模型调用之前就已发出，取消不应把它抹掉");
        assertFalse(events.isEmpty());
    }
}
