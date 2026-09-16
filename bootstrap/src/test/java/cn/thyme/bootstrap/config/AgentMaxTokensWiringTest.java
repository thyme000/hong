package cn.thyme.bootstrap.config;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 配置里的 {@code max-tokens} 是否真的走到了运行时。
 *
 * <p>只导入 {@link AgentAutoConfiguration}，并用 {@code @MockitoBean} 顶掉真实客户端。
 * provider 的 url 指向死端口是刻意的探针：一旦替换失效、真客户端真被创建出来，
 * 它自带的健康检查必然失败并让上下文起不来——所以这个测试能跑通，就说明用的是
 * 替换后的客户端，不依赖本机有没有起 Ollama。</p>
 */
@SpringBootTest(classes = AgentMaxTokensWiringTest.Config.class)
@TestPropertySource(properties = {
        "llm.default-provider=ollama",
        "llm.providers.ollama.url=http://localhost:1/v1",
        "llm.providers.ollama.models[0]=fake-model",
        "llm.providers.ollama.max-tokens=1500"
})
class AgentMaxTokensWiringTest {

    /** 只装配 agent 这一层；不加载工具、记忆、Web。 */
    @Configuration
    @Import(AgentAutoConfiguration.class)
    static class Config {
    }

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    private Agent mainAgent;

    @Test
    void 配置maxTokens应改变运行时实际使用的上限() {
        // 永远返回工具调用、每轮报 400 用量，因此循环只能靠预算刹车。
        // 工具名不存在也无妨——执行失败同样算「工具轮」，循环照常推进。
        AtomicInteger calls = new AtomicInteger();
        given(llmClient.chat(any(), any())).willAnswer(invocation -> new LlmResponse("fake-model", null,
                List.of(new LlmToolCall("c" + calls.incrementAndGet(), "noop", Map.of())),
                new LlmUsage(400, 0)));

        AgentResult result = mainAgent.run(
                AgentContext.builder().task("随便做点什么").sessionId("wiring-test").build());

        System.out.println(">> 停止原因: " + result.stopReason()
                + "，轮次=" + result.iterations() + "，已用=" + result.totalTokens());

        assertEquals(AgentStopReason.TOKEN_BUDGET, result.stopReason(),
                "按配置的 1500 触发预算上限；若配置没生效会一路跑到轮次上限");
        assertTrue(result.totalTokens() < 2000,
                "应在配置的小阈值附近收手，而不是累积到 AgentConfig 的默认上限 100000");
    }
}
