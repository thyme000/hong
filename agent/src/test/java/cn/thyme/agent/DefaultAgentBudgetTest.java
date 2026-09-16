package cn.thyme.agent;

import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmUsage;
import cn.thyme.tool.ExecutionMode;
import cn.thyme.tool.Tool;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolResult;
import cn.thyme.tool.security.Capability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环内的预算检查：不再只在「工具调用轮结束后」比一次累计用量。
 *
 * <p>这里覆盖两条原来会漏网的路径：</p>
 * <ul>
 *   <li>直接给出最终答案的那一轮——不经过工具调用，原检查完全绕过；</li>
 *   <li>单次调用的输入本身就超预算——此时请求根本不该发出去。</li>
 * </ul>
 */
class DefaultAgentBudgetTest {

    /** 够大的工具输出：足以让下一轮的输入估算明显抬升。 */
    private static final String BIG_PAYLOAD = "长".repeat(2000);

    /** 什么都不做、只回一段固定文本的工具，让循环能进入工具调用轮。 */
    private record EchoTool(String payload) implements Tool {
        @Override
        public String name() {
            return "noop";
        }

        @Override
        public String description() {
            return "空操作";
        }

        @Override
        public ExecutionMode executionMode() {
            return ExecutionMode.SYNC_ONLY;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext context) {
            return ToolResult.success(payload);
        }
    }

    /** 第 1 轮调一次工具，第 2 轮直接给答案；两轮各自报告 600 的用量。 */
    private static final class ToolThenAnswerClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            if (calls.incrementAndGet() == 1) {
                return new LlmResponse("m", null,
                        List.of(new LlmToolCall("c1", "noop", Map.of())), new LlmUsage(600, 0));
            }
            return new LlmResponse("m", "最终答案", List.of(), new LlmUsage(600, 0));
        }
    }

    /** 永远调工具，从不收尾；每轮报给定用量。 */
    private static final class LoopingClient implements LlmClient {
        private final int usagePerCall;
        private final AtomicInteger calls = new AtomicInteger();

        LoopingClient(int usagePerCall) {
            this.usagePerCall = usagePerCall;
        }

        /** 供测试断言「请求是否真的发出去了」。 */
        int calls() {
            return calls.get();
        }

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            Integer usage = usagePerCall < 0 ? null : usagePerCall;
            return new LlmResponse("m", null,
                    List.of(new LlmToolCall("c" + calls.incrementAndGet(), "noop", Map.of())),
                    usage == null ? null : new LlmUsage(usage, 0));
        }
    }

    /** 无用量信息，逼走字符估算路径。 */
    private static final class NoUsageEchoClient implements LlmClient {
        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            String echoed = request.messages().stream()
                    .map(LlmMessage::content)
                    .filter(c -> c != null)
                    .reduce((a, b) -> b)
                    .orElse("");
            return new LlmResponse("m", echoed, List.of(), null);
        }
    }

    private DefaultAgent agentOf(LlmClient client, String payload, int maxTokens) {
        return new DefaultAgent(AgentConfig.builder()
                .name("t")
                .llmClient(client)
                .model("m")
                .systemPrompt("x")
                .tools(List.of(new EchoTool(payload)))
                .maxTokens(maxTokens)
                .build());
    }

    private static AgentContext contextOf(String task) {
        return AgentContext.builder().task(task).sessionId("s-1").build();
    }

    @Test
    void 直接给出最终答案的那一轮也应受预算约束() {
        // 预算 1000：首轮报 600，余 400；而首轮那大段工具输出把第二轮的输入估算
        // 推到 500 左右，于是这一轮不该发出请求——原实现只在工具轮结束后检查，
        // 这一轮会直接带着答案返回，看上去「成功」。
        AgentResult result = agentOf(new ToolThenAnswerClient(), BIG_PAYLOAD, 1000)
                .run(contextOf("做点事"));

        System.out.println(">> 停止原因: " + result.stopReason() + "，已用 " + result.totalTokens());

        assertEquals(AgentStopReason.INPUT_BUDGET_EXCEEDED, result.stopReason(),
                "第二轮请求应被拦下，而不是给出「成功」的最终答案");
        assertNull(result.content(), "被中断时不应有最终内容");
        assertEquals(600, result.totalTokens(), "只有第一轮真正花掉了额度");
    }

    @Test
    void 预算充裕时应正常跑完两轮并给出答案() {
        AgentResult result = agentOf(new ToolThenAnswerClient(), BIG_PAYLOAD, 100_000)
                .run(contextOf("做点事"));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals("最终答案", result.content());
        assertEquals(1200, result.totalTokens(), "两轮用量都应计入");
    }

    @Test
    void 工具轮结束后预算见底应立即停下() {
        // 每轮报 400、预算 1000：第 3 轮结束后累计 1200 越线，循环在此收手
        AgentResult result = agentOf(new LoopingClient(400), "ok", 1000)
                .run(contextOf("做点事"));

        System.out.println(">> 停止原因: " + result.stopReason() + "，已用 " + result.totalTokens());

        assertEquals(AgentStopReason.TOKEN_BUDGET, result.stopReason());
        assertEquals(1200, result.totalTokens(), "越线的那一轮用量也应计入");
        assertNull(result.content());
    }

    @Test
    void 输入体积超预算时不应发出请求() {
        // 厂商不返回用量，输入估算 = 全部消息字符数 / 4。
        // 首轮结束后回填的大段工具输出让第二轮估算冲到 500 以上，超过余量，
        // 此时应当直接放弃，而不是把注定超额的请求发出去。
        LoopingClient client = new LoopingClient(-1);
        AgentResult result = agentOf(client, BIG_PAYLOAD, 300).run(contextOf("做点事"));

        System.out.println(">> 停止原因: " + result.stopReason() + "，实际发出 " + client.calls() + " 次请求");

        assertEquals(AgentStopReason.INPUT_BUDGET_EXCEEDED, result.stopReason());
        assertEquals(1, client.calls(), "超额的请求不该发出去");
    }

    @Test
    void 无用量信息时按字符估算而不是按字符计数() {
        // 400 字的任务：输入约 401 字符、输出约 400 字符，chars/4 口径下约 200。
        // 若单位漂移成「字符数」，这里会得到 800 上下，据此可发现口径不一致。
        AgentResult result = agentOf(new NoUsageEchoClient(), "ok", 100_000)
                .run(contextOf("长".repeat(400)));

        System.out.println(">> 无用量路径估算: " + result.totalTokens());
        assertTrue(result.totalTokens() >= 100 && result.totalTokens() < 400,
                "应为 chars/4 的量级（约 200），实际 " + result.totalTokens());
    }
}
