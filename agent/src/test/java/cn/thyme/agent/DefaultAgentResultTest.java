package cn.thyme.agent;

import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAgent} 的结果口径：
 * <ul>
 *   <li>模型返回空内容 → 抛 {@link EmptyModelResponseException}，不静默变成空串</li>
 *   <li>被轮次 / token 上限截断 → {@code content} 为 null、{@code truncated=true}，
 *       不再往 content 里塞「达到上限」这类状态文案</li>
 * </ul>
 */
class DefaultAgentResultTest {

    /** 固定返回同一内容的假客户端。 */
    private record StubLlmClient(LlmResponse response) implements LlmClient {
        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            return response;
        }
    }

    /** 永远返回工具调用的假客户端：用于逼出轮次上限。 */
    private static final class LoopingLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            int n = calls.incrementAndGet();
            return new LlmResponse("test-model", null,
                    List.of(new LlmToolCall("call-" + n, "unknown_tool", Map.of())), null);
        }
    }

    /**
     * 每次返回 1 个工具调用，且厂商报的 usage 很大：用于把累计用量顶穿预算，
     * 同时保持输入体积很小（否则调用前的输入估算会先一步触发
     * {@link AgentStopReason#INPUT_BUDGET_EXCEEDED}）。
     */
    private static final class HeavyUsageLoopingLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            int n = calls.incrementAndGet();
            return new LlmResponse("test-model", null,
                    List.of(new LlmToolCall("call-" + n, "unknown_tool", Map.of())),
                    new LlmUsage(500, 0));
        }
    }

    private DefaultAgent agentOf(LlmClient client, int maxIterations, int maxTokens) {
        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(client)
                .model("test-model")
                .systemPrompt("你是测试 agent")
                .maxIterations(maxIterations)
                .maxTokens(maxTokens)
                .build();
        return new DefaultAgent(config);
    }

    private AgentContext context() {
        return AgentContext.builder().task("随便说点什么").sessionId("s-1").build();
    }

    @Test
    void content为null时应抛出EmptyModelResponseException() {
        DefaultAgent agent = agentOf(new StubLlmClient(new LlmResponse("test-model", null, List.of(), null)), 3, 100_000);

        EmptyModelResponseException e = assertThrows(EmptyModelResponseException.class, () -> agent.run(context()));
        assertTrue(e.getMessage().contains("空内容"));
    }

    @Test
    void content为空白串时应抛出EmptyModelResponseException() {
        DefaultAgent agent = agentOf(
                new StubLlmClient(new LlmResponse("test-model", "   \n  ", List.of(), null)), 3, 100_000);

        assertThrows(EmptyModelResponseException.class, () -> agent.run(context()));
    }

    @Test
    void content正常时应原样返回() {
        DefaultAgent agent = agentOf(
                new StubLlmClient(new LlmResponse("test-model", "任务已完成", List.of(), null)), 3, 100_000);

        AgentResult result = agent.run(context());

        assertEquals("任务已完成", result.content());
        assertEquals(1, result.iterations());
        assertFalse(result.truncated());
    }

    @Test
    void 达到轮次上限时应中断且无最终内容() {
        DefaultAgent agent = agentOf(new LoopingLlmClient(), 2, 100_000);

        AgentResult result = agent.run(context());

        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason());
        assertNull(result.content(), "中断时不应有最终内容");
        assertEquals(2, result.iterations());
    }

    @Test
    void 累计用量越线时应以TOKEN_BUDGET中断() {
        // 每轮报 500 token 的 usage、输入体积却很小：第一轮过后累计即越线。
        // 若换成输入体积大的场景，调用前的输入估算会先触发 INPUT_BUDGET_EXCEEDED
        // ——这是预期行为，因为工具结果不断堆进上下文，输入估算通常先于累计用量越线。
        DefaultAgent agent = agentOf(new HeavyUsageLoopingLlmClient(), 50, 100);

        AgentResult result = agent.run(context());

        assertEquals(AgentStopReason.TOKEN_BUDGET, result.stopReason());
        assertNull(result.content(), "中断时不应有最终内容");
        assertEquals(1, result.iterations());
    }

    @Test
    void 输入估算越线时应以INPUT_BUDGET_EXCEEDED中断且一次调用都不发() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient counting = (request, deltaListener) -> {
            calls.incrementAndGet();
            return new LlmResponse("test-model", "不该被调用", List.of(), null);
        };
        // maxTokens=1：连首次调用的输入估算（系统提示词 + 任务）都过不了
        DefaultAgent agent = agentOf(counting, 10, 1);

        AgentResult result = agent.run(context());

        assertEquals(AgentStopReason.INPUT_BUDGET_EXCEEDED, result.stopReason());
        assertNull(result.content());
        assertEquals(0, calls.get(), "判定输入超预算后不应再发起 LLM 调用");
        assertEquals(0, result.iterations(), "一次调用都没发，轮次应为 0");
    }

    @Test
    void 工厂方法应各自产出合法结果() {
        assertNull(AgentResult.truncated(3, 100, AgentStopReason.TOKEN_BUDGET, "s-1").content());
        assertEquals("done", AgentResult.completed("done", 1, 10, "s-1").content());
        assertNotNull(AgentResult.completed("done", 1, 10, "s-1"));
    }

    @Test
    void AgentResult不应允许非法组合() {
        // 正常结束却没有内容
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult("", 1, 0, AgentStopReason.COMPLETED, "s-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult("  ", 1, 0, AgentStopReason.COMPLETED, "s-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult(null, 1, 0, AgentStopReason.COMPLETED, "s-1"));
        // 中断了却带着最终内容
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult("达到轮次上限", 1, 0, AgentStopReason.MAX_ITERATIONS, "s-1"));
        // 中断原因不能是 COMPLETED
        assertThrows(IllegalArgumentException.class,
                () -> AgentResult.truncated(1, 0, AgentStopReason.COMPLETED, "s-1"));
    }
}
