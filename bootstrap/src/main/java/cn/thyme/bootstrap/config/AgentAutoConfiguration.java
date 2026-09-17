package cn.thyme.bootstrap.config;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentConfig;
import cn.thyme.agent.AgentFactory;
import cn.thyme.agent.PromptTemplate;
import cn.thyme.agent.event.AgentEventPublisher;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.bootstrap.memory.TitleGenerator;
import cn.thyme.bootstrap.stream.ActiveRuns;
import cn.thyme.bootstrap.stream.SessionRunQueue;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.providers.OllamaLlmClient;
import cn.thyme.llm.providers.SiliconFlowLlmClient;
import cn.thyme.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 主 agent 装配：读 {@link LlmProperties} 的默认提供商创建 {@link LlmClient}
 * （带健康检查），并创建主 agent（hong）。事件发布器分发给容器内所有 {@link AgentListener}。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class AgentAutoConfiguration {

    @Value("${agent.system-prompt-file:prompts/general.md}")
    private String systemPromptFile;

    /** 标题生成的提示词模板；与系统提示词分开，因为它只有两个变量且约束不同。 */
    @Value("${agent.title.prompt-file:prompts/title.md}")
    private String titlePromptFile;

    /** 子 agent 自己的轮次上限：比主 agent 收敛，避免一次委派吃掉整轮对话。 */
    @Value("${agent.sub-agent.max-iterations:8}")
    private int subAgentMaxIterations;

    @Bean
    public AgentEventPublisher agentEventPublisher(List<AgentListener> listeners) {
        return new AgentEventPublisher(listeners);
    }

    /**
     * 流式运行的发送线程池：每条并发会话占一个线程（模型调用是阻塞式的）。
     * 用有界队列 + CallerRunsPolicy，负载满时让提交方自己跑，天然形成背压，
     * 而不是静默丢任务或无限堆积。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentRunExecutor(
            @Value("${agent.stream.threads:8}") int threads,
            @Value("${agent.stream.queue-capacity:64}") int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-run");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** 同一会话的任务排队串行；跨会话并行。 */
    @Bean
    public SessionRunQueue sessionRunQueue(ExecutorService agentRunExecutor) {
        return new SessionRunQueue(agentRunExecutor);
    }

    /**
     * 标题改写的专用线程池。<b>不复用 {@code agentRunExecutor}</b>：那个池用的是
     * CallerRunsPolicy，而 {@code Finished} 事件是在用户请求线程上同步分发的——
     * 池满时提交方会就地执行，把一次 best-effort 的标题生成变成用户请求的尾延迟。
     * 这里饱和时直接丢弃：占位标题已经在库里了，下一个成功轮次还会再触发一次。
     *
     * <p>返回类型刻意写成具体的 {@link ThreadPoolExecutor} 而不是 {@code ExecutorService}：
     * {@code sessionRunQueue} 与 {@code SessionTitleService} 都按 {@code ExecutorService}
     * 注入，而后者靠 {@code @Qualifier} 区分、前者只靠参数名。声明成接口后容器里就有两个
     * {@code ExecutorService} 候选，本模块开着 {@code -parameters}，参数名恰好与 bean 名
     * 相同所以<b>现在</b>能解析成功——但那是巧合而非保证：参数名或 bean 名任一被改，
     * 故障就会在启动期以 {@code NoUniqueBeanDefinitionException} 的形式出现。
     * 返回具体类型让「同一时刻只有一个 ExecutorService 候选」在类型层面成立，
     * 不依赖命名。别为了「统一风格」把它改成 ExecutorService。</p>
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor sessionTitleExecutor(
            @Value("${agent.title.threads:2}") int threads,
            @Value("${agent.title.queue-capacity:32}") int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "session-title");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 会话标题生成器：复用主 agent 的模型与系统提示词来源（同一份 provider 配置），
     * 但走 {@code prompts/title.md} 这个专门的模板与独立的 LlmClient 调用。
     */
    @Bean
    public TitleGenerator titleGenerator(LlmClient llmClient, LlmProperties props) {
        String provider = props.getDefaultProvider();
        LlmProperties.ProviderProperties p = props.getProviders().get(provider);
        String model = p == null || p.getModels() == null || p.getModels().isEmpty()
                ? null
                : p.getModels().get(0);
        return new TitleGenerator(llmClient, model, PromptTemplate.fromClasspath(titlePromptFile));
    }

    /** 活跃流式运行登记 + 心跳；心跳间隔为 0 表示关闭（测试用）。 */
    @Bean(destroyMethod = "shutdown")
    public ActiveRuns activeRuns(@Value("${agent.stream.heartbeat:15s}") Duration heartbeat) {
        return new ActiveRuns(heartbeat);
    }

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        String provider = props.getDefaultProvider();
        LlmProperties.ProviderProperties p = props.getProviders().get(provider);
        if (p == null) {
            throw new IllegalStateException("未配置模型提供商: " + provider);
        }
        if (p.getModels() == null || p.getModels().isEmpty()) {
            throw new IllegalStateException("提供商未配置模型: " + provider);
        }
        LlmClient client = createClient(provider, p);
        healthCheck(client, p.getModels().get(0));
        return client;
    }

    /**
     * 主 agent 与子 agent 共用同一批普通工具；委派工具只挂在主 agent 上。
     * 子 agent 不再持有委派工具，因此委派深度固定为 1 层——避免嵌套委派把
     * 预算与延迟放大到不可控。
     */
    @Bean
    public Agent mainAgent(LlmClient llmClient, LlmProperties props, List<Tool> tools,
                           AgentEventPublisher eventPublisher) {
        String provider = props.getDefaultProvider();
        LlmProperties.ProviderProperties p = props.getProviders().get(provider);
        String model = p.getModels().get(0);
        AgentFactory factory = new AgentFactory();

        // 子 agent 是另一个 agent 实例，但共用同一个 LlmClient / 事件发布器，
        // 并挂上（自己的）子 agent 工具，从而支持多层委派
        AgentConfig subConfig = AgentConfig.builder()
                .name("hong-sub")
                .llmClient(llmClient)
                .model(model)
                .systemPromptClasspath(systemPromptFile)
                .tools(tools)
                .maxIterations(subAgentMaxIterations)
                .temperature(p.getTemperature())
                .eventPublisher(eventPublisher)
                .build();
        Tool subAgentTool = factory.createSubAgentTool(
                "delegate_task",
                "把一个较复杂的子任务交给独立上下文的子 agent 执行，返回它的文字结论。",
                subConfig, "", subAgentMaxIterations);

        AgentConfig.Builder builder = AgentConfig.builder()
                .name("hong")
                .llmClient(llmClient)
                .model(model)
                .systemPromptClasspath(systemPromptFile)
                .tools(concat(tools, subAgentTool))
                .temperature(p.getTemperature())
                .eventPublisher(eventPublisher);
        // 未配置时保持 AgentConfig 的默认上限，不在这里补一个平行的默认值
        if (p.getMaxTokens() != null) {
            builder.maxTokens(p.getMaxTokens());
        }
        return factory.create(builder.build());
    }

    /** 合并常规工具与委派工具；委派工具单独传，避免子 agent 把常规工具也带上。 */
    private static List<Tool> concat(List<Tool> tools, Tool extra) {
        List<Tool> result = new ArrayList<>(tools);
        result.add(extra);
        return List.copyOf(result);
    }

    private LlmClient createClient(String provider, LlmProperties.ProviderProperties p) {
        return switch (provider) {
            case "ollama" -> new OllamaLlmClient(p.getUrl());
            case "siliconflow" -> new SiliconFlowLlmClient(p.getUrl(), p.getApikey());
            default -> throw new IllegalStateException("不支持的模型提供商: " + provider);
        };
    }

    private void healthCheck(LlmClient client, String model) {
        for (int i = 1; i <= 3; i++) {
            try {
                client.chat(LlmRequest.builder().model(model).addUserMessage("ping").build());
                return;
            } catch (Exception e) {
                if (i == 3) {
                    throw new IllegalStateException("模型健康检查失败（重试 3 次）: " + model, e);
                }
            }
        }
    }
}
