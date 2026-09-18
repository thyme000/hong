package cn.thyme.bootstrap;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentConfig;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.DefaultAgent;
import cn.thyme.agent.SubAgentTool;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.bootstrap.memory.ConversationHistoryListener;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.MemoryStore;
import cn.thyme.memory.db.DatabaseMemoryStore;
import cn.thyme.memory.db.MemoryDatabaseConfig;
import cn.thyme.memory.db.MemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话历史验证：agent 发出的事件经监听器落到 SQLite，可按会话回看。
 * 用假 LlmClient（不依赖 Ollama），只加载 memory-db 上下文。
 */
@SpringBootTest(classes = ConversationHistorySqliteTest.Config.class)
@TestPropertySource(properties = {
        "memory.store.type=sqlite",
        "spring.datasource.url=jdbc:sqlite:./target/test-history.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "mybatis.mapper-locations=classpath*:/mapper/**/*.xml"
})
class ConversationHistorySqliteTest {

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("cn.thyme.memory.db")
    @Import(MemoryDatabaseConfig.class)
    static class Config {
        @Bean
        MemoryStore memoryStore(MemoryMapper mapper, ObjectMapper json) {
            return new DatabaseMemoryStore(mapper, json);
        }
    }

    @Autowired
    private MemoryStore store;

    @BeforeEach
    void clean() {
        store.list().forEach(r -> store.delete(r.id()));
    }

    @Test
    @DisplayName("会话历史：user + assistant 消息落 SQLite 并可回看")
    void persistsConversationHistory() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store);
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(listener));

        // 假 LlmClient：直接返回文本，不调真实模型
        LlmClient fakeClient = (request, deltaListener) -> new LlmResponse("fake-model", "这是测试回复", List.of(), null);

        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(fakeClient)
                .model("fake-model")
                .systemPrompt("你是测试助手")
                .eventPublisher(publisher)
                .build();
        Agent agent = new DefaultAgent(config);

        AgentResult result = agent.run(
                AgentContext.builder().task("测试任务").sessionId("s-test").build());
        System.out.println(">> agent 结果: " + result.content());

        List<MemoryRecord> history = listener.history("s-test");
        System.out.println(">> 会话历史条数: " + history.size());
        history.forEach(r -> System.out.println("   [" + r.metadata().get("role") + "] " + r.content()));

        assertEquals(2, history.size());
        assertEquals("USER", history.get(0).metadata().get("role"));
        assertEquals("测试任务", history.get(0).content());
        assertEquals("ASSISTANT", history.get(1).metadata().get("role"));
        assertEquals("这是测试回复", history.get(1).content());

        // 会话列表接口已改由 SessionStore 承载，这里不再断言从消息反推出的列表
    }

    /**
     * 归属指针必须在<b>真实 SQLite</b> 上验证：内存实现里 metadata 就是个 Map，
     * 而这里 {@code parentSessionId} 要经 JSON 落库、再由 {@code json_extract} 查回来。
     * 两条路径完全不同，只在内存分支测等于没测。
     */
    @Test
    @DisplayName("委派的子会话消息落 SQLite，删除主会话按 parentSessionId 一并清掉")
    void deletesSubSessionHistoryOnSqlite() {
        ConversationHistoryListener listener = new ConversationHistoryListener(store);
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(listener));

        AtomicInteger mainCalls = new AtomicInteger();
        LlmClient mainClient = (request, deltaListener) -> {
            if (mainCalls.incrementAndGet() == 1) {
                return new LlmResponse("fake-model", null,
                        List.of(new LlmToolCall("c1", "delegate_task", Map.of("task", "查一下", "budget", "SMALL"))),
                        null);
            }
            return new LlmResponse("fake-model", "问过了", List.of(), null);
        };
        LlmClient subClient = (request, deltaListener) ->
                new LlmResponse("fake-model", "子 agent 的结论", List.of(), null);

        SubAgentTool delegate = new SubAgentTool("delegate_task", "委派", budget -> new DefaultAgent(
                AgentConfig.builder()
                        .name("sub").llmClient(subClient).model("fake-model")
                        .systemPrompt("你是子 agent").maxTokens(1000).budget(budget)
                        .eventPublisher(publisher)
                        .build()),
                "");

        AgentConfig config = AgentConfig.builder()
                .name("test")
                .llmClient(mainClient)
                .model("fake-model")
                .systemPrompt("你是测试助手")
                .tools(List.of(delegate))
                .maxTokens(1000)
                .eventPublisher(publisher)
                .build();

        new DefaultAgent(config).run(
                AgentContext.builder().task("帮我查点东西").sessionId("s-main").build());

        // 从库里反查子会话 id：这一步同时证明 parentSessionId 真的写进了 JSON
        List<String> subIds = store.list().stream()
                .filter(r -> "s-main".equals(r.metadata().get("parentSessionId")))
                .map(r -> (String) r.metadata().get("sessionId"))
                .distinct()
                .toList();
        System.out.println(">> 子会话 id = " + subIds);
        assertEquals(1, subIds.size(), "应恰好有一个派生的子会话");
        String subId = subIds.get(0);
        assertTrue(subId.startsWith("s-main-sub-"), "派生子会话 id 形如 s-main-sub-xxx: " + subId);
        assertFalse(listener.history(subId).isEmpty(), "子会话消息应已落库");

        int removed = listener.deleteSession("s-main");
        System.out.println(">> 删除条数 = " + removed);

        assertTrue(listener.history("s-main").isEmpty());
        assertTrue(listener.history(subId).isEmpty(), "子会话历史必须一并清掉");
        assertTrue(store.list().stream()
                        .noneMatch(r -> "s-main".equals(r.metadata().get("parentSessionId"))),
                "库里不该再有任何指向 s-main 的子会话记录");
    }
}
