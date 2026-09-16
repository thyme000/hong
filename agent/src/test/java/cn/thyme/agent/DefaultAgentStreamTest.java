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
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.ThinkingLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAgent} 对流式增量的转发：模型每吐一片就发一个
 * {@link AgentEvent.Delta}，整轮结束时再发一条完整的 {@link AgentEvent.Message}。
 *
 * <p>关键是「增量只推送、Message 才落库」这条分工——若增量也走 Message，
 * 一次回答会变成上百条历史记录。</p>
 */
class DefaultAgentStreamTest {

    /** 假客户端：先把预置增量回调出去，再返回完整响应。 */
    private static final class StreamingLlmClient implements LlmClient {

        private final List<LlmDelta> deltas;
        private final LlmResponse response;

        StreamingLlmClient(List<LlmDelta> deltas, LlmResponse response) {
            this.deltas = deltas;
            this.response = response;
        }

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            deltas.forEach(deltaListener);
            return response;
        }
    }

    private static AgentResult run(LlmClient client, AgentListener listener, AgentContext context) {
        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(client)
                .model("test-model")
                .systemPrompt("")
                .maxIterations(3)
                .eventPublisher(new AgentEventPublisher(List.of(listener)))
                .build();
        return new DefaultAgent(config).run(context);
    }

    private static List<LlmDelta> words(String... texts) {
        List<LlmDelta> deltas = new ArrayList<>();
        for (String text : texts) {
            deltas.add(LlmDelta.content(text));
        }
        return deltas;
    }

    @Test
    void 模型增量应逐片转发为Delta事件() {
        List<AgentEvent> events = new ArrayList<>();
        LlmClient client = new StreamingLlmClient(words("你", "好", "世界"),
                new LlmResponse("test-model", "你好世界", null, null));

        AgentResult result = run(client, events::add, AgentContext.builder().task("打个招呼").build());

        List<String> deltaTexts = events.stream()
                .filter(AgentEvent.Delta.class::isInstance)
                .map(e -> ((AgentEvent.Delta) e).text())
                .toList();
        assertEquals(List.of("你", "好", "世界"), deltaTexts);
        assertEquals("你好世界", result.content());
    }

    @Test
    void 完整文本只发一条Message而增量不发Message() {
        List<AgentEvent> events = new ArrayList<>();
        LlmClient client = new StreamingLlmClient(words("你", "好", "世界"),
                new LlmResponse("test-model", "你好世界", null, null));

        run(client, events::add, AgentContext.builder().task("打个招呼").build());

        List<AgentEvent.Message> messages = events.stream()
                .filter(AgentEvent.Message.class::isInstance)
                .map(AgentEvent.Message.class::cast)
                .filter(m -> m.role() == LlmRole.ASSISTANT)
                .toList();
        // 一条完整消息，而不是三条增量——这是历史不被污染的保证
        assertEquals(1, messages.size());
        assertEquals("你好世界", messages.get(0).content());
    }

    @Test
    void 思考增量应标记为reasoning且不混入最终文本() {
        List<AgentEvent> events = new ArrayList<>();
        List<LlmDelta> deltas = List.of(LlmDelta.reasoning("让我想想"), LlmDelta.content("答案"));
        LlmClient client = new StreamingLlmClient(deltas,
                new LlmResponse("test-model", "答案", null, null));

        AgentResult result = run(client, events::add, AgentContext.builder().task("问题").build());

        List<AgentEvent.Delta> deltaEvents = events.stream()
                .filter(AgentEvent.Delta.class::isInstance)
                .map(AgentEvent.Delta.class::cast)
                .toList();
        assertEquals(1, deltaEvents.stream().filter(AgentEvent.Delta::reasoning).count());
        assertEquals("让我想想", deltaEvents.get(0).text());
        assertEquals("答案", result.content());
    }

    @Test
    void 传入的思考档位应落到请求上() {
        List<ThinkingLevel> seen = new ArrayList<>();
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
                seen.add(request.thinking());
                return new LlmResponse("test-model", "答案", null, null);
            }
        };

        run(client, e -> {}, AgentContext.builder().task("问题").thinking(ThinkingLevel.LOW).build());

        assertEquals(List.of(ThinkingLevel.LOW), seen);
    }

    @Test
    void 未指定思考档位时请求里应是null即关闭() {
        List<ThinkingLevel> seen = new ArrayList<>();
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
                seen.add(request.thinking());
                return new LlmResponse("test-model", "答案", null, null);
            }
        };

        run(client, e -> {}, AgentContext.builder().task("问题").build());

        assertEquals(1, seen.size());
        assertNull(seen.get(0));
    }

    @Test
    void 工具调用的增量也应转发给本次运行的监听器() {
        List<AgentEvent> events = new ArrayList<>();
        // 第一轮吐增量并请求工具，第二轮给最终答案
        LlmClient client = new LlmClient() {
            private int calls;

            @Override
            public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
                if (++calls == 1) {
                    deltaListener.accept(LlmDelta.content("先查一下"));
                    return new LlmResponse("test-model", null,
                            List.of(new LlmToolCall("c1", "unknown_tool", Map.of())), null);
                }
                deltaListener.accept(LlmDelta.content("查完了"));
                return new LlmResponse("test-model", "查完了", null, null);
            }
        };

        AgentResult result = run(client, events::add, AgentContext.builder().task("查点东西").build());

        List<String> deltaTexts = events.stream()
                .filter(AgentEvent.Delta.class::isInstance)
                .map(e -> ((AgentEvent.Delta) e).text())
                .toList();
        assertEquals(List.of("先查一下", "查完了"), deltaTexts);
        assertEquals("查完了", result.content());
        assertTrue(events.stream().anyMatch(AgentEvent.ToolCall.class::isInstance));
    }

    @Test
    void 运行专属监听器应独占本次事件() {
        List<AgentEvent> global = new ArrayList<>();
        List<AgentEvent> scoped = new ArrayList<>();
        LlmClient client = new StreamingLlmClient(words("好"),
                new LlmResponse("test-model", "好", null, null));

        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(client)
                .model("test-model")
                .systemPrompt("")
                .maxIterations(3)
                .eventPublisher(new AgentEventPublisher(List.<AgentListener>of(global::add)))
                .build();
        AgentContext context = AgentContext.builder()
                .task("打个招呼")
                .listeners(List.<AgentListener>of(scoped::add))
                .build();

        new DefaultAgent(config).run(context);

        assertTrue(global.isEmpty(), "运行专属监听器生效时全局监听器不应再收到事件");
        assertTrue(scoped.stream().anyMatch(AgentEvent.Delta.class::isInstance));
    }

    @Test
    void 消息内容应原样进入历史而不是被增量替换() {
        List<LlmMessage> history = List.of(LlmMessage.user("上一轮的问题"));
        List<AgentEvent> events = new ArrayList<>();
        LlmClient client = new StreamingLlmClient(words("答", "案"),
                new LlmResponse("test-model", "答案", null, null));

        run(client, events::add, AgentContext.builder().task("新问题").history(history).build());

        List<AgentEvent.Message> userMessages = events.stream()
                .filter(AgentEvent.Message.class::isInstance)
                .map(AgentEvent.Message.class::cast)
                .filter(m -> m.role() == LlmRole.USER)
                .toList();
        assertEquals(1, userMessages.size());
        assertEquals("新问题", userMessages.get(0).content());
    }
}
