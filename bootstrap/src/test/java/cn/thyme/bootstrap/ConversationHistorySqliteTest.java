package cn.thyme.bootstrap;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentConfig;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.DefaultAgent;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.bootstrap.memory.ConversationHistoryListener;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmResponse;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(listener.sessions().contains("s-test"));
    }
}
