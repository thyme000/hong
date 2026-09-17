package cn.thyme.bootstrap.memory;

import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.InMemorySessionStore;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.MemoryStore;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import cn.thyme.agent.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTitleServiceTest {

    private static final PromptTemplate PROMPT = PromptTemplate.fromClasspath("prompts/title.md");

    private SessionStore sessions;
    private MemoryStore store;
    private ConversationHistoryListener history;
    /** 每次调用都数一遍，用来断言「该不该调模型」。 */
    private AtomicInteger modelCalls;
    private ThreadPoolExecutor executor;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionStore();
        store = new InMemoryMemoryStore();
        history = new ConversationHistoryListener(store);
        modelCalls = new AtomicInteger();
        client = (request, listener) -> {
            modelCalls.incrementAndGet();
            return new LlmResponse("fake", "生成的标题", List.of(), null);
        };
        executor = executor(2, 32);
    }

    private static ThreadPoolExecutor executor(int threads, int queue) {
        return new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "test-title");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    private SessionTitleService service() {
        return new SessionTitleService(sessions, history, new TitleGenerator(client, "fake", PROMPT),
                executor, true);
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    /** 落一条会话历史：用户消息 + 助手回复。 */
    private void seedExchange(String sessionId) {
        store.save(new MemoryRecord(sessionId + "--1", "服务高峰期总是超时",
                Map.of("role", "USER", "sessionId", sessionId)));
        store.save(new MemoryRecord(sessionId + "--2", "先看日志",
                Map.of("role", "ASSISTANT", "sessionId", sessionId)));
    }

    private static AgentResult completed(String sessionId) {
        return AgentResult.completed("回答", 1, 10, sessionId);
    }

    /** 等异步改写落地（best-effort 异步，测试里必须显式等）。 */
    private void awaitIdle() throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "标题改写未能及时完成");
    }

    @Test
    @DisplayName("正常完成：标题被改写为 GENERATED")
    void rewritesOnCompleted() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        service().submit("s1", completed("s1"));
        awaitIdle();

        assertEquals("生成的标题", sessions.get("s1").orElseThrow().title());
        assertEquals(TitleSource.GENERATED, sessions.get("s1").orElseThrow().titleSource());
        assertEquals(1, modelCalls.get());
        print("标题 -> " + sessions.get("s1").orElseThrow().title() + " / generated");
    }

    @Test
    @DisplayName("闸一：会话行不存在（子会话）时跳过，不调模型")
    void skipsSubSession() throws Exception {
        // 子会话：历史是有的（消息落在同一张表），但 session_record 里没有它的行
        seedExchange("s1-sub-xyz");

        service().submit("s1-sub-xyz", completed("s1-sub-xyz"));
        awaitIdle();

        assertEquals(0, modelCalls.get(), "子会话不该触发标题生成");
        print("子会话 -> 一次模型调用都没有");
    }

    @Test
    @DisplayName("闸二：非 COMPLETED 的轮次不触发")
    void skipsNonCompleted() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        for (AgentStopReason reason : new AgentStopReason[]{
                AgentStopReason.CANCELLED,
                AgentStopReason.MAX_ITERATIONS,
                AgentStopReason.TOKEN_BUDGET,
                AgentStopReason.INPUT_BUDGET_EXCEEDED}) {
            service().submit("s1", AgentResult.truncated(1, 10, reason, "s1"));
        }
        awaitIdle();

        assertEquals(0, modelCalls.get(), "只有 COMPLETED 才该生成标题");
        assertEquals(TitleSource.AUTO, sessions.get("s1").orElseThrow().titleSource());
        print("取消/上限/预算越线 -> 一次模型调用都没有");
    }

    @Test
    @DisplayName("闸三：来源已不是 AUTO 时跳过")
    void skipsWhenAlreadyGenerated() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        sessions.updateTitleIfAuto("s1", "已有的标题", TitleSource.GENERATED, Instant.now());
        seedExchange("s1");

        service().submit("s1", completed("s1"));
        awaitIdle();

        assertEquals(0, modelCalls.get());
        assertEquals("已有的标题", sessions.get("s1").orElseThrow().title());
        print("已生成过 -> 跳过，省一次调用");
    }

    @Test
    @DisplayName("闸三：用户改过名后，再跑一轮也不会被改写回去")
    void userTitleSurvivesLaterRounds() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        sessions.rename("s1", "我自己起的", Instant.now());
        seedExchange("s1");

        service().submit("s1", completed("s1"));
        awaitIdle();

        assertEquals(0, modelCalls.get());
        assertEquals("我自己起的", sessions.get("s1").orElseThrow().title());
        assertEquals(TitleSource.USER, sessions.get("s1").orElseThrow().titleSource());
        print("用户改名后 -> 保持 user，未被改写");
    }

    @Test
    @DisplayName("首轮被打断后，下一轮成功仍能补上改写")
    void succeedsOnLaterRoundAfterInterrupted() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        // 第一轮被取消：闸二拦下，来源保持 AUTO
        service().submit("s1", AgentResult.truncated(1, 10, AgentStopReason.CANCELLED, "s1"));
        awaitIdle();
        assertEquals(TitleSource.AUTO, sessions.get("s1").orElseThrow().titleSource());
        print("第一轮取消 -> 仍是 auto");

        // 第二轮成功：三道闸都放行
        executor = executor(2, 32);
        service().submit("s1", completed("s1"));
        awaitIdle();
        assertEquals(TitleSource.GENERATED, sessions.get("s1").orElseThrow().titleSource());
        print("第二轮成功 -> 补上 generated");
    }

    @Test
    @DisplayName("模型调用抛异常时不外抛，标题保持原样")
    void modelFailureIsSwallowed() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");
        LlmClient failing = (request, listener) -> {
            throw new IllegalStateException("模型炸了");
        };

        new SessionTitleService(sessions, history, new TitleGenerator(failing, "fake", PROMPT),
                executor, true).submit("s1", completed("s1"));
        awaitIdle();

        assertEquals("占位标题", sessions.get("s1").orElseThrow().title());
        assertEquals(TitleSource.AUTO, sessions.get("s1").orElseThrow().titleSource());
        print("模型异常 -> 保留占位标题且来源不变，下次还能再试");
    }

    @Test
    @DisplayName("开关关闭时完全不提交")
    void disabledFlagSkipsEverything() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        new SessionTitleService(sessions, history, new TitleGenerator(client, "fake", PROMPT),
                executor, false).submit("s1", completed("s1"));
        awaitIdle();

        assertEquals(0, modelCalls.get());
        assertEquals(TitleSource.AUTO, sessions.get("s1").orElseThrow().titleSource());
        print("agent.title.enabled=false -> 不生成");
    }

    @Test
    @DisplayName("是否自动（不占调用方线程）：submit 应立刻返回")
    void submitReturnsImmediately() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        // 一个慢模型：若 submit 是同步的，这里要等 1 秒
        CountDownLatch started = new CountDownLatch(1);
        LlmClient slow = (request, listener) -> {
            started.countDown();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmResponse("fake", "慢标题", List.of(), null);
        };
        SessionTitleService service = new SessionTitleService(sessions, history,
                new TitleGenerator(slow, "fake", PROMPT), executor, true);

        long before = System.nanoTime();
        service.submit("s1", completed("s1"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - before).toMillis();
        print("submit 耗时 -> " + elapsedMs + "ms（模型要跑 1000ms）");

        // 提交发生在用户请求线程上，必须立刻返回——慢的是后台那个线程
        assertTrue(elapsedMs < 500, "submit 不该阻塞调用方，实际 " + elapsedMs + "ms");
        assertTrue(started.await(5, TimeUnit.SECONDS), "后台线程应已开始调用模型");
    }

    @Test
    @DisplayName("取首个用户消息与首个助手回复；工具记录不参与")
    void collectsFirstExchange() {
        String sessionId = "s1";
        // 工具记录落在用户消息之前：role 是 TOOL_CALL，不得被当成用户消息
        store.save(new MemoryRecord(sessionId + "--1", "read_file {}",
                Map.of("role", "TOOL_CALL", "sessionId", sessionId)));
        store.save(new MemoryRecord(sessionId + "--2", "真实提问",
                Map.of("role", "USER", "sessionId", sessionId)));
        store.save(new MemoryRecord(sessionId + "--3", "工具结果",
                Map.of("role", "TOOL_RESULT", "sessionId", sessionId)));
        store.save(new MemoryRecord(sessionId + "--4", "真实回答",
                Map.of("role", "ASSISTANT", "sessionId", sessionId)));

        String[] exchange = service().firstExchange(sessionId);
        print("提问 -> " + exchange[0] + " / 回答 -> " + exchange[1]);

        assertEquals("真实提问", exchange[0]);
        assertEquals("真实回答", exchange[1]);
    }

    @Test
    @DisplayName("没有用户消息时返回 null")
    void returnsNullWithoutUserMessage() {
        assertNull(service().firstExchange("empty"));
        print("没有用户消息 -> null");
    }

    @Test
    @DisplayName("缺少回答时只拿提问生成（不整轮放弃）")
    void generatesFromQuestionAloneWhenAnswerMissing() {
        store.save(new MemoryRecord("s1--1", "只有提问",
                Map.of("role", "USER", "sessionId", "s1")));

        String[] exchange = service().firstExchange("s1");
        print("回答缺失 -> 提问=" + exchange[0] + ", 回答=" + exchange[1]);

        assertEquals("只有提问", exchange[0]);
        assertEquals("", exchange[1]);
    }

    @Test
    @DisplayName("DiscardPolicy：线程池饱和时丢弃而不是在调用方线程上跑")
    void discardsWhenSaturated() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");

        // 单线程 + 容量 1 + 两个占位任务 → 池与队列都满
        ThreadPoolExecutor tiny = executor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        tiny.execute(blocker);
        tiny.execute(blocker);

        // 模型必须是慢的：CallerRunsPolicy 下提交方会就地跑完整条链路（含模型调用），
        // 用瞬时模型的话两种策略耗时都是 0ms，这条测试就分辨不出任何东西
        LlmClient slow = (request, listener) -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmResponse("fake", "标题", List.of(), null);
        };
        SessionTitleService service = new SessionTitleService(sessions, history,
                new TitleGenerator(slow, "fake", PROMPT), tiny, true);

        long before = System.nanoTime();
        service.submit("s1", completed("s1"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - before).toMillis();
        release.countDown();
        print("饱和时 submit 耗时 -> " + elapsedMs + "ms（模型要跑 1000ms）");

        assertTrue(elapsedMs < 500, "饱和时也不该在调用方线程上执行，实际 " + elapsedMs + "ms");
        tiny.shutdownNow();
    }

    @Test
    @DisplayName("会话在运行期间被删：改写命中 0 行，不报错")
    void toleratesSessionDeletedMidRun() throws Exception {
        sessions.createIfAbsent("s1", "占位标题", TitleSource.AUTO, Instant.now());
        seedExchange("s1");
        // 模拟 DELETE 与排队的改写并发：submit 之前行就没了
        sessions.delete("s1");

        service().submit("s1", completed("s1"));
        awaitIdle();

        assertEquals(0, modelCalls.get());
        assertFalse(sessions.get("s1").isPresent());
        print("会话已删 -> 静默跳过");
    }
}
