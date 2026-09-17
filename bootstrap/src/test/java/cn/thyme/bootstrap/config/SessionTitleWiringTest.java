package cn.thyme.bootstrap.config;

import cn.thyme.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 标题改写线程池的装配属性：这些都不是风格问题，改错了会让用户请求变慢或直接起不来。
 *
 * <p>只导入 {@link AgentAutoConfiguration}，用 {@code @MockitoBean} 顶掉真实客户端
 * （provider url 指向死端口当探针，与 {@code AgentMaxTokensWiringTest} 同例）。</p>
 */
@SpringBootTest(classes = SessionTitleWiringTest.Config.class)
@TestPropertySource(properties = {
        "llm.default-provider=ollama",
        "llm.providers.ollama.url=http://localhost:1/v1",
        "llm.providers.ollama.models[0]=fake-model"
})
class SessionTitleWiringTest {

    @Configuration
    @Import(AgentAutoConfiguration.class)
    static class Config {
    }

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    @Qualifier("sessionTitleExecutor")
    private ThreadPoolExecutor sessionTitleExecutor;

    @Autowired
    private ExecutorService agentRunExecutor;

    @Test
    void 必须是独立的线程池而不是复用agentRunExecutor() {
        // 复用的话，agentRunExecutor 的 CallerRunsPolicy 会让池满时在用户请求线程上
        // 就地跑完整条标题生成链路，把一次 best-effort 的改写变成用户请求的尾延迟
        System.out.println(">> sessionTitleExecutor=" + sessionTitleExecutor
                + ", agentRunExecutor=" + agentRunExecutor);

        assertNotSame(agentRunExecutor, sessionTitleExecutor);
    }

    @Test
    void 饱和时必须是丢弃而不是在调用方线程上执行() {
        System.out.println(">> 拒绝策略=" + sessionTitleExecutor.getRejectedExecutionHandler().getClass().getSimpleName());

        // 占位标题已经在库里了，下一个成功轮次还会再触发一次，丢弃是安全的；
        // 而 CallerRunsPolicy 会把同步分发 Finished 的那条用户请求线程拖住
        assertInstanceOf(ThreadPoolExecutor.DiscardPolicy.class,
                sessionTitleExecutor.getRejectedExecutionHandler());
    }

    @Test
    void 必须是有界队列的固定大小池() {
        System.out.println(">> corePoolSize=" + sessionTitleExecutor.getCorePoolSize()
                + ", maxPoolSize=" + sessionTitleExecutor.getMaximumPoolSize()
                + ", queue=" + sessionTitleExecutor.getQueue().getClass().getSimpleName()
                + ", remainingCapacity=" + sessionTitleExecutor.getQueue().remainingCapacity());

        // 无界队列会让积压在负载下无声增长，而不是触发丢弃
        assertEquals(sessionTitleExecutor.getCorePoolSize(), sessionTitleExecutor.getMaximumPoolSize(),
                "固定大小池");
        assertInstanceOf(java.util.concurrent.ArrayBlockingQueue.class, sessionTitleExecutor.getQueue());
    }
}
