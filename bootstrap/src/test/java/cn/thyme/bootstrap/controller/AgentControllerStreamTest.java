package cn.thyme.bootstrap.controller;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentCancelledException;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.bootstrap.memory.ConversationHistoryListener;
import cn.thyme.bootstrap.memory.HistoryRenderer;
import cn.thyme.bootstrap.stream.ActiveRuns;
import cn.thyme.bootstrap.stream.AgentRun;
import cn.thyme.bootstrap.stream.SessionRunQueue;
import cn.thyme.bootstrap.web.GlobalExceptionHandler;
import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.llm.model.LlmRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式端点的帧格式与取消时序。
 *
 * <p>不用 {@code @SpringBootTest}：控制器的协作者都有明确的构造器，手工装配比让
 * Spring 去猜 {@code List<AgentListener>} 该注入什么更直接，跑得也快。整棵上下文的
 * 装配由 {@code HongApplicationTests} 覆盖。</p>
 *
 * <p>不测真实 {@link cn.thyme.agent.DefaultAgent}：这里验证的是「事件 → 帧」的映射
 * 与取消时序，用一个按脚本发事件的假 agent 能把这两件事钉死；真实 agent 的循环
 * 行为在 agent 模块自己的测试里覆盖。</p>
 */
class AgentControllerStreamTest {

    private ExecutorService executor;
    private SessionRunQueue queue;
    private ActiveRuns activeRuns;
    private RecordingAgent agent;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        executor = Executors.newFixedThreadPool(4);
        queue = new SessionRunQueue(executor);
        // 心跳设成 1 小时：本测试只关心帧内容，不需要后台线程插进来搅局
        activeRuns = new ActiveRuns(Duration.ofHours(1));
        agent = new RecordingAgent();

        ConversationHistoryListener history = mock(ConversationHistoryListener.class);
        given(history.history(any())).willReturn(List.of());

        AgentController sync = new AgentController(
                agent, history, new HistoryRenderer(), new SessionWorkspace(tempDir.toString()));
        StreamingAgentController streaming = new StreamingAgentController(
                agent, sync, queue, activeRuns, List.of());

        mockMvc = MockMvcBuilders.standaloneSetup(streaming)
                // 显式挂上真实 advice：缺 sessionId 的 400 由它决定，
                // 不是测试自己造出来的
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        activeRuns.shutdown();
        executor.shutdownNow();
    }

    @Test
    void 增量应逐帧推送并以finished收尾() throws Exception {
        agent.script = (context, emit) -> {
            emit.accept(delta(context, "你", false));
            emit.accept(delta(context, "好", false));
            emit.accept(message(context, "你好"));
            return completed(context, "你好");
        };

        String body = runToCompletion("s-frames", "打个招呼");

        assertTrue(body.contains("event:delta"), "应有 delta 帧:\n" + body);
        assertTrue(body.contains("\"text\":\"你\""), "第一片增量应原样推送:\n" + body);
        assertTrue(body.contains("\"text\":\"好\""), "第二片增量应原样推送:\n" + body);
        assertTrue(body.contains("event:finished"), "应有收尾帧:\n" + body);
        assertTrue(body.contains("\"stopReason\":\"COMPLETED\""), "正常结束:\n" + body);
        assertTrue(body.contains("\"content\":\"你好\""), "收尾帧应带上完整答案:\n" + body);
    }

    /** 终稿由 finished 帧承载；再发一帧 Message 会让前端把同一段文本渲染两遍。 */
    @Test
    void 终稿不应既走Message帧又走finished帧() throws Exception {
        agent.script = (context, emit) -> {
            emit.accept(message(context, "唯一的答案"));
            return completed(context, "唯一的答案");
        };

        String body = runToCompletion("s-dedupe", "问");

        assertTrue(body.contains("event:finished"), "应有收尾帧:\n" + body);
        assertTrue(!body.contains("event:message"), "Message 不应单独成帧:\n" + body);
        assertEquals(1, countOf(body, "唯一的答案"),
                "同一段文本只该出现一次（在 finished 帧里）:\n" + body);
    }

    @Test
    void 思考增量应走reasoning事件名() throws Exception {
        agent.script = (context, emit) -> {
            emit.accept(delta(context, "先想一下", true));
            emit.accept(delta(context, "答案", false));
            return completed(context, "答案");
        };

        String body = runToCompletion("s-think", "问");

        assertTrue(body.contains("event:reasoning"), "思考应走 reasoning:\n" + body);
        assertTrue(body.contains("event:delta"), "回答仍走 delta:\n" + body);
    }

    @Test
    void 工具调用与结果应各成一帧() throws Exception {
        agent.script = (context, emit) -> {
            emit.accept(new AgentEvent.ToolCall(context.sessionId(), Instant.now(),
                    "c1", "noop", Map.of("path", "a.txt")));
            emit.accept(new AgentEvent.ToolResult(context.sessionId(), Instant.now(),
                    "c1", "noop", "读到了", true));
            return completed(context, "好了");
        };

        String body = runToCompletion("s-tool", "读文件");

        assertTrue(body.contains("event:tool_call"), "应有工具调用帧:\n" + body);
        assertTrue(body.contains("\"toolName\":\"noop\""), "应带工具名:\n" + body);
        assertTrue(body.contains("event:tool_result"), "应有工具结果帧:\n" + body);
        assertTrue(body.contains("\"result\":\"读到了\""), "应带工具结果:\n" + body);
        assertTrue(body.contains("\"ok\":true"), "应带成败标记:\n" + body);
    }

    @Test
    void 缺少sessionId时应报400而不是开流() throws Exception {
        mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"task\":\"没有会话\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 第一帧写出后响应头就已提交，{@code @RestControllerAdvice} 再也改不了状态码。
     * 所以失败必须就地变成 error 帧——否则前端只会看到连接被无声掐断。
     */
    @Test
    void 模型抛异常时应以error帧收尾() throws Exception {
        agent.script = (context, emit) -> {
            emit.accept(delta(context, "开了个头", false));
            throw new IllegalStateException("模型炸了");
        };

        String body = runToCompletion("s-error", "问");

        assertTrue(body.contains("event:error"), "应以 error 帧收尾:\n" + body);
        assertTrue(body.contains("模型炸了"), "应带上失败原因:\n" + body);
        assertTrue(!body.contains("event:finished"), "失败就不该再报完成:\n" + body);
    }

    /**
     * 取消：收尾帧<b>立刻</b>发出（不等下一个增量，因为它可能很久不来），
     * 且此后到达的增量一律不再外泄。
     *
     * <p>「立刻」只体现在收尾帧上：运行线程可能正阻塞在模型调用里，那是外部 IO，
     * 拦不住；真正让它停下来的是下一个事件处的 {@link AgentCancelledException}。</p>
     */
    @Test
    void 取消应立即收尾且不再推送此后的增量() throws Exception {
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        agent.script = (context, emit) -> {
            emit.accept(delta(context, "半截", false));
            firstDelta.countDown();
            await(release);
            // 抵达时运行已被取消，AgentRun 应先于发送就把这一帧拦下
            emit.accept(delta(context, "不该出现", false));
            return completed(context, "不该出现的完整答案");
        };

        MvcResult result = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s-cancel\",\"task\":\"长任务\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertTrue(firstDelta.await(5, TimeUnit.SECONDS), "应已收到第一片增量");

        mockMvc.perform(post("/agent/sessions/s-cancel/cancel"))
                .andExpect(status().isOk());

        // 不等运行线程：取消自己就把流收掉了，收尾帧此刻就该在
        String body = bodyOf(result);
        assertTrue(body.contains("event:finished"), "取消也应发收尾帧:\n" + body);
        assertTrue(body.contains("\"stopReason\":\"CANCELLED\""), "停止原因应为 CANCELLED:\n" + body);
        assertTrue(!body.contains("event:error"), "取消是调用方的意图，不是故障:\n" + body);

        release.countDown();
        assertTrue(agent.awaitRunDone(5), "运行线程应已结束");
        assertNoEscape("取消");

        // 再读一次：此刻运行线程已跑完，任何外泄的帧都会出现在响应体里
        String after = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(!after.contains("不该出现"), "取消后的增量不应外泄:\n" + after);
        assertTrue(!after.contains("event:message"), "半截回答不应落成终稿:\n" + after);
    }

    /**
     * 运行已收尾后到达的 {@link AgentEvent.Finished} 不得把异常抛出 {@code run}。
     *
     * <p>{@code DefaultAgent} 在 catch 块内发这个事件，此处抛出会直接穿过它「取消
     * 不外抛」的契约跑到线程池里——线程池只会把它咽掉并打一行日志，调用方与客户端
     * 都看不见，是最容易漏掉的一类退化。</p>
     */
    @Test
    void 收尾事件在被取消的运行上不应让异常逃出run() throws Exception {
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        agent.script = (context, emit) -> {
            emit.accept(delta(context, "半截", false));
            firstDelta.countDown();
            await(release);
            return completed(context, "不该出现的完整答案");
        };

        MvcResult result = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s-escape\",\"task\":\"长任务\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertTrue(firstDelta.await(5, TimeUnit.SECONDS), "应已收到第一片增量");
        mockMvc.perform(post("/agent/sessions/s-escape/cancel")).andExpect(status().isOk());

        release.countDown();
        // 等 run 自己结束再断言：流的收尾是取消触发的，早于 run 返回
        assertTrue(agent.awaitRunDone(5), "运行线程应已结束");
        assertNoEscape("被取消的运行");
        bodyOf(result);
    }

    /** 没有在跑的会话，取消是空操作而不是报错（前端可以在任何时机发）。 */
    @Test
    void 取消未在运行的会话应返回false() throws Exception {
        mockMvc.perform(post("/agent/sessions/没在跑的会话/cancel"))
                .andExpect(status().isOk());
    }

    /**
     * 客户端断开（刷新页面、关标签）必须让后台的 agent 停下来。
     *
     * <p>否则一次断连就留下一棵空转的 agent 树继续烧 token，而调用方早已不在。
     * 触发点是 {@code SseEmitter} 的 {@code onCompletion} 生命周期回调——它不带数据，
     * 只通知「连接没了」，正是「断连即取消」唯一的落点。</p>
     *
     * <p>这一条不走 MockMvc：{@code asyncDispatch} 会一直阻塞到请求处理结束，而断连
     * 恰恰要在运行中途发生、运行还得继续跑到被打断为止，两者时序上不可能凑到一起。
     * 所以直接驱动 emitter 生命周期回调——那是被测契约的真实触发点。容器确实会
     * 调它，行为在 {@code ResponseBodyEmitter} 的 {@code DefaultCallback} 里。</p>
     */
    @Test
    void 客户端断开应让运行停下来() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        AgentRun run = new AgentRun("s-disconnect", emitter);
        AtomicBoolean closed = new AtomicBoolean();
        run.onClose(() -> closed.set(true));

        assertFalse(run.isCancelled(), "刚建好时不该是已取消");

        // 容器发现客户端不在了：这是「断连即取消」唯一的触发点
        fireCompletionCallback(emitter);

        assertTrue(closed.get(), "断连应触发收尾清理");
        assertTrue(run.isCancelled(), "断连应把运行标记为已取消");

        // 断连后的下一个事件必须把运行打断，而不是继续往外推
        AgentContext context = AgentContext.builder().task("长任务").sessionId("s-disconnect").build();
        AgentCancelledException thrown = assertThrows(AgentCancelledException.class,
                () -> run.onEvent(delta(context, "断连后的增量", false)),
                "断连后的增量应被拦下，否则 agent 会继续空转烧 token");
        assertTrue(thrown.getMessage().contains("关闭"), "应说明是因为流已关闭: " + thrown.getMessage());
    }

    /**
     * 触发 {@code SseEmitter} 的完成回调，等价于容器发现客户端已经不在了。
     *
     * <p>反射是刻意的：走「容器真正会调的那个钩子」，而不是另造一个后门去改状态，
     * 否则测的是测试自己而不是生产代码。</p>
     */
    private static void fireCompletionCallback(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("completionCallback");
        field.setAccessible(true);
        ((Runnable) field.get(emitter)).run();
    }

    /**
     * 同一会话排队串行，不同会话并行。
     *
     * <p>并列跑会让两次运行各自读到不含对方结果的旧历史、并各自按 seq 落库，
     * 上下文与历史双双错乱——所以这条顺序是正确性要求，不是性能取舍。</p>
     */
    @Test
    void 同一会话的两次运行应排队串行() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        AtomicInteger runs = new AtomicInteger();
        agent.script = (context, emit) -> {
            int index = runs.incrementAndGet();
            order.add("开始" + index);
            if (index == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            order.add("结束" + index);
            return completed(context, "第" + index + "次");
        };

        MvcResult first = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s-queue\",\"task\":\"第一个\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "第一个任务应已开始");

        MvcResult second = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s-queue\",\"task\":\"第二个\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 第二个必须完全没开始：它要等第一个跑完
        assertEquals(List.of("开始1"), List.copyOf(order),
                "第二个任务不应在第一个结束前开始");

        releaseFirst.countDown();
        bodyOf(first);
        bodyOf(second);

        assertEquals(List.of("开始1", "结束1", "开始2", "结束2"), List.copyOf(order),
                "同一会话的两条流必须首尾相接，不能交错");
    }

    @Test
    void 不同会话应可以并行() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        agent.script = (context, emit) -> {
            bothRunning.countDown();
            await(release);
            return completed(context, "好了");
        };

        MvcResult a = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"parallel-a\",\"task\":\"甲\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult b = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"parallel-b\",\"task\":\"乙\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertTrue(bothRunning.await(5, TimeUnit.SECONDS),
                "跨会话的运行应能同时进行（线程池够用）");

        release.countDown();
        bodyOf(a);
        bodyOf(b);
    }

    /** 提交请求并等异步分派完成，返回响应体。 */
    private String runToCompletion(String sessionId, String task) throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/chat/stream")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"" + sessionId + "\",\"task\":\"" + task + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return bodyOf(result);
    }

    /**
     * 等异步分派完成并取出响应体。
     *
     * <p>超时不能直接 {@code getContentAsString()}——MockHttpServletResponse 在
     * 请求未完成时返回的是此刻已写入的部分而不是报错，会让断言以「帧缺失」的面目
     * 失败，掩盖真正的卡死。</p>
     */
    private static String bodyOf(MvcResult result) throws Exception {
        try {
            result.getAsyncResult(10_000);
        } catch (IllegalStateException e) {
            throw new AssertionError("流在 10 秒内未收尾，实际已写入:\n"
                    + result.getResponse().getContentAsString(StandardCharsets.UTF_8), e);
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** {@code run} 不应把任何异常抛给调用方——线程池会把它咽掉，故障就此隐身。 */
    private void assertNoEscape(String scenario) {
        Throwable escaped = agent.escaped;
        if (escaped != null) {
            throw new AssertionError(scenario + "的 run 抛出了异常，契约要求就地转成结果", escaped);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static AgentEvent.Delta delta(AgentContext context, String text, boolean reasoning) {
        return new AgentEvent.Delta(context.sessionId(), Instant.now(), text, reasoning);
    }

    private static AgentEvent.Message message(AgentContext context, String content) {
        return new AgentEvent.Message(context.sessionId(), Instant.now(), LlmRole.ASSISTANT, content);
    }

    private static AgentResult completed(AgentContext context, String content) {
        return AgentResult.completed(content, 1, 0, context.sessionId());
    }

    /** 一次运行的脚本：用 {@code emit} 发事件，返回结果。 */
    @FunctionalInterface
    interface Script {
        AgentResult apply(AgentContext context, Listener emit);
    }

    @FunctionalInterface
    interface Listener {
        void accept(AgentEvent event);
    }

    /**
     * 按脚本发事件的假 agent。
     *
     * <p>三条与 {@link cn.thyme.agent.DefaultAgent} 对齐的契约，缺一测试就不成立：</p>
     * <ul>
     *   <li>结尾必须发 {@link AgentEvent.Finished}——「跑完了」这件事由它通知，
     *       流靠它收尾，不是靠 {@code run} 返回；</li>
     *   <li>{@code Finished} 在 catch 块内发出，与 {@code DefaultAgent.finish} 同构。
     *       监听器若在它上面抛异常，会直接穿过 {@code run}——这正是
     *       {@link #escaped} 要抓的那种破坏；</li>
     *   <li>把 {@link AgentCancelledException} 就地转成 {@code CANCELLED} 结果，
     *       不上抛——否则取消会被误当成故障走到 error 帧上去。</li>
     * </ul>
     */
    static final class RecordingAgent implements Agent {

        volatile Script script = (context, emit) -> completed(context, "默认答案");
        /** {@code run} 逃出去的异常。契约要求这里永远是 null。 */
        volatile Throwable escaped;
        /** {@code run} 最终返回的结果；调用方据此断言运行是怎么结束的。 */
        volatile AgentResult lastResult;
        /**
         * {@code run} 已返回或已抛出。
         *
         * <p>不能靠「流收尾」来推断运行线程已结束：取消会先把流收掉，而运行线程
         * 那时可能还阻塞着。要断言「没异常逃出去」，只能等 {@code run} 自己结束。</p>
         */
        private final CountDownLatch runDone = new CountDownLatch(1);
        private final AtomicInteger iterations = new AtomicInteger();

        @Override
        public AgentResult run(AgentContext context) {
            try {
                AgentResult result = execute(context);
                lastResult = result;
                return result;
            } catch (RuntimeException e) {
                escaped = e;
                throw e;
            } finally {
                runDone.countDown();
            }
        }

        private AgentResult execute(AgentContext context) {
            try {
                AgentResult result = script.apply(context, event -> publish(context, event));
                finish(context, result);
                return result;
            } catch (AgentCancelledException e) {
                AgentResult cancelled = AgentResult.truncated(iterations.get(), 0,
                        AgentStopReason.CANCELLED, context.sessionId());
                // 在 catch 块内发收尾，与 DefaultAgent.finish 的位置一致：在这里抛出
                // 会直接穿过 run，而不是被上面那个 catch 接住
                finish(context, cancelled);
                return cancelled;
            }
        }

        private void finish(AgentContext context, AgentResult result) {
            publish(context, new AgentEvent.Finished(context.sessionId(), Instant.now(), result));
        }

        boolean awaitRunDone(long seconds) {
            try {
                return runDone.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private static void publish(AgentContext context, AgentEvent event) {
            for (AgentListener listener : context.listeners()) {
                listener.onEvent(event);
            }
        }
    }
}
