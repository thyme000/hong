package cn.thyme.agent;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmUsage;
import cn.thyme.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派链路端到端：主 agent 调用委派工具 → 子 agent 执行 → 结论回到主 agent，
 * 且子 agent 的消耗计入同一个预算池。
 */
class SubAgentDelegationTest {

    /** 主 agent：第一轮委派，拿到子 agent 结论后给出最终答案。 */
    private static final class DelegatingMainClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
            if (calls.incrementAndGet() == 1) {
                return new LlmResponse("m", null,
                        List.of(new LlmToolCall("c1", "delegate_task",
                                Map.of("task", "查一下天气", "budget", "SMALL"))), null);
            }
            return new LlmResponse("m", "子 agent 说：" + lastToolResult(request), List.of(), null);
        }

        /** 从请求里取回最后一次工具结果，用于证明子 agent 的结论确实回传了。 */
        private static String lastToolResult(LlmRequest request) {
            return request.messages().stream()
                    .filter(m -> !m.toolResults().isEmpty())
                    .reduce((a, b) -> b)
                    .map(m -> m.toolResults().get(0).content())
                    .orElse("<无>");
        }
    }

    private static LlmClient subAgentClient(String answer, int usagePerCall) {
        return (request, deltaListener) -> new LlmResponse("s", answer, List.of(), new LlmUsage(usagePerCall, 0));
    }

    private static LlmRequest firstRequestOf(String task) {
        return LlmRequest.builder().model("m").addUserMessage(task).build();
    }

    @Test
    void 委派后子agent的结论应回传主agent并计入共享预算() {
        AgentConfig subConfig = AgentConfig.builder()
                .name("sub")
                .llmClient(subAgentClient("晴，25 度", 70))
                .model("s")
                .systemPrompt("你是子 agent")
                .maxTokens(1000)
                .build();
        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派", budget -> new DefaultAgent(
                AgentConfig.builder()
                        .name("sub").llmClient(subConfig.llmClient()).model("s")
                        .systemPrompt("你是子 agent").maxTokens(1000).budget(budget).build()),
                "");

        AgentConfig mainConfig = AgentConfig.builder()
                .name("main")
                .llmClient(new DelegatingMainClient())
                .model("m")
                .systemPrompt("你是主 agent")
                .tools(List.of(delegate))
                .maxTokens(1000)
                .build();

        AgentResult result = new DefaultAgent(mainConfig).run(
                AgentContext.builder().task("今天天气如何").sessionId("s1").build());

        System.out.println(">> 主 agent 最终答案: " + result.content());
        System.out.println(">> 全树 token: " + result.totalTokens());

        assertTrue(result.content().contains("晴，25 度"), "子 agent 的结论应回传到主 agent");
        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
    }

    @Test
    void 委派产生的子运行必须带上父会话指针() {
        // 归属关系靠这个指针建立，而不是靠 id 前缀猜。历史落库据此把子会话的
        // 消息标记成属于主会话，删除主会话时才能连带清掉
        List<String> subEventSessions = new java.util.ArrayList<>();
        List<String> subEventParents = new java.util.ArrayList<>();
        AgentListener recorder = event -> {
            if (event instanceof AgentEvent.Message message
                    && message.sessionId().startsWith("s1-sub-")) {
                subEventSessions.add(message.sessionId());
                subEventParents.add(message.parentSessionId());
            }
        };

        // 子 agent 必须与主 agent 共用同一个发布器（线上由 AgentAutoConfiguration 接的
        // 就是这条线），否则子运行的事件根本到不了监听器，指针也就无从观察
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(recorder));

        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派", budget -> new DefaultAgent(
                AgentConfig.builder()
                        .name("sub").llmClient(subAgentClient("晴，25 度", 70)).model("s")
                        .systemPrompt("你是子 agent").maxTokens(1000).budget(budget)
                        .eventPublisher(publisher).build()),
                "");

        AgentConfig mainConfig = AgentConfig.builder()
                .name("main")
                .llmClient(new DelegatingMainClient())
                .model("m")
                .systemPrompt("你是主 agent")
                .tools(List.of(delegate))
                .eventPublisher(publisher)
                .maxTokens(1000)
                .build();

        new DefaultAgent(mainConfig).run(
                AgentContext.builder().task("今天天气如何").sessionId("s1").build());

        assertFalse(subEventSessions.isEmpty(), "子运行必须发出过消息事件");
        assertEquals(List.of("s1"), subEventParents.stream().distinct().toList(),
                "子运行事件的父指针必须指向主会话");
        assertTrue(subEventSessions.stream().noneMatch("s1"::equals),
                "子会话用的是派生子 id，不该与主会话 id 相同");
    }

    @Test
    void 预算耗尽时委派应快速失败而不是启动子agent() {
        // 划拨时父预算已经见底：委派工具必须直接返回失败
        AgentBudget exhausted = new AgentBudget(100);
        exhausted.spend(100);

        AtomicInteger subCalls = new AtomicInteger();
        LlmClient counting = (request, deltaListener) -> {
            subCalls.incrementAndGet();
            return new LlmResponse("s", "不该被调用", List.of(), null);
        };
        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派",
                budget -> new DefaultAgent(AgentConfig.builder()
                        .name("sub").llmClient(counting).model("s")
                        .systemPrompt("x").maxTokens(1000).budget(budget).build()),
                "");

        var result = delegate.execute(Map.of("task", "随便"),
                new ToolContext("s1", exhausted));

        assertFalse(result.ok());
        assertTrue(result.text().contains("预算"), "应明确说明是预算问题");
        assertEquals(0, subCalls.get(), "预算耗尽时不应真的启动子 agent");
    }

    @Test
    void 未识别档位应回落到默认档而不是失败() {
        AgentBudget parent = new AgentBudget(1000);
        AtomicInteger subCalls = new AtomicInteger();
        LlmClient counting = (request, deltaListener) -> {
            subCalls.incrementAndGet();
            return new LlmResponse("s", "完成", List.of(), null);
        };
        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派",
                budget -> new DefaultAgent(AgentConfig.builder()
                        .name("sub").llmClient(counting).model("s")
                        .systemPrompt("x").maxTokens(1000).budget(budget).build()),
                "");

        var result = delegate.execute(
                Map.of("task", "随便", "budget", "HUGE"), new ToolContext("s1", parent));

        assertTrue(result.ok(), "无法识别的档位应回落到 MEDIUM，而不是让委派失败");
        assertEquals(1, subCalls.get());
    }

    @Test
    void 子agent的历史应隔离但工作区应跟随父会话() {
        // 记录子 agent 运行的上下文：sessionId 用于取父会话沙箱，
        // 关键是不能沿用父会话的历史（否则污染父上下文）
        java.util.concurrent.atomic.AtomicReference<AgentContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        LlmClient capture = (request, deltaListener) -> new LlmResponse("s", "完成", List.of(), null);
        Agent recording = context -> {
            seen.set(context);
            return new DefaultAgent(AgentConfig.builder()
                    .name("sub").llmClient(capture).model("s")
                    .systemPrompt("x").maxTokens(1000).build()).run(context);
        };
        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派", budget -> recording, "");

        delegate.execute(Map.of("task", "读一下父会话的文件"),
                new ToolContext("parent-session", new AgentBudget(1000)));

        AgentContext subContext = seen.get();
        assertNotNull(subContext, "子 agent 应被启动");
        assertEquals("parent-session", subContext.workspaceSessionId(),
                "工作区会话必须沿用父会话，否则子 agent 看不到用户上传的文件");
        assertTrue(subContext.sessionId().startsWith("parent-session-sub-"),
                "历史会话应是派生子会话，与父会话隔离");
        assertTrue(subContext.sessionId().contains("-sub-"),
                "派生 id 只含安全字符（它会被当作沙箱目录名）");
    }

    @Test
    void 子agent的消耗应计入父预算() {
        AgentBudget parent = new AgentBudget(1000);
        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派",
                budget -> new DefaultAgent(AgentConfig.builder()
                        .name("sub").llmClient(subAgentClient("完成", 250)).model("s")
                        .systemPrompt("x").maxTokens(1000).budget(budget).build()),
                "");

        var result = delegate.execute(Map.of("task", "随便"), new ToolContext("s1", parent));

        assertTrue(result.ok());
        assertEquals(250, parent.used(), "子 agent 花掉的应记在父预算上");
    }
}
