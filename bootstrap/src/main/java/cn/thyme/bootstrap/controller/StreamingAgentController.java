package cn.thyme.bootstrap.controller;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.agent.event.AgentListener;
import cn.thyme.bootstrap.stream.ActiveRuns;
import cn.thyme.bootstrap.stream.AgentRun;
import cn.thyme.bootstrap.stream.SessionRunQueue;
import cn.thyme.llm.model.ThinkingLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流式对话入口：模型产出的过程以 SSE 逐帧推送，而不是等整轮跑完。
 *
 * <p>与同步的 {@code POST /agent/chat} 是并列的两条路，互不影响——云端调用方与
 * 脚本直接用同步接口，前端页面走流式接口。</p>
 *
 * <p>帧格式：{@code event:} 名即事件类型（{@code delta} / {@code reasoning} /
 * {@code tool_call} / {@code tool_result} / {@code finished} / {@code error}），
 * 前端按事件名分发，不必解析 data 里的类型字段。</p>
 */
@RestController
public class StreamingAgentController {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentController.class);

    /**
     * 单次运行的最长时长。取得比较大是因为模型冷加载可能耗时数分钟
     * （实测 6.5GB 模型冷启动 6 分钟以上），卡在这里会让正常请求被误杀。
     * 靠心跳帧维持连接，不靠它来限制真正的耗时。
     */
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(15);

    private final Agent mainAgent;
    private final AgentController agentController;
    private final SessionRunQueue queue;
    private final ActiveRuns activeRuns;
    /** 全局监听器（历史落库、日志）。流式运行也要带上，否则历史不再落库。 */
    private final List<AgentListener> globalListeners;

    public StreamingAgentController(Agent mainAgent, AgentController agentController,
                                    SessionRunQueue queue, ActiveRuns activeRuns,
                                    List<AgentListener> globalListeners) {
        this.mainAgent = mainAgent;
        this.agentController = agentController;
        this.queue = queue;
        this.activeRuns = activeRuns;
        this.globalListeners = globalListeners == null ? List.of() : List.copyOf(globalListeners);
    }

    /**
     * 提交一条任务并以 SSE 流式返回。
     *
     * <p>body 可带 {@code thinking}（{@code none} / {@code low} / {@code medium} /
     * {@code high}）：由调用方按轮次决定深度思考档位。<b>缺省与不认识的值都是
     * {@code none}，即显式关闭</b>——不下发该参数并不等于关闭，只会让模型按自己的
     * 默认走，而推理模型的默认是开。不加部署级开关，因为同一次部署里不同请求的取值
     * 本就不同。</p>
     */
    @PostMapping(value = "/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body) {
        String sessionId = AgentController.requireSessionId(body);
        String task = body.get("task");
        ThinkingLevel thinking = parseThinking(body.get("thinking"));

        SseEmitter emitter = new SseEmitter(RUN_TIMEOUT.toMillis());
        AgentRun run = new AgentRun(sessionId, emitter);
        activeRuns.register(run);
        run.onClose(() -> activeRuns.unregister(run));

        queue.submit(sessionId, () -> execute(run, sessionId, task, thinking));
        return emitter;
    }

    /**
     * 取消某会话正在进行的运行。
     *
     * @return 是否确实取消了一次运行；没有在跑则返回 false
     */
    @PostMapping("/agent/sessions/{sessionId}/cancel")
    public Map<String, Object> cancel(@PathVariable String sessionId) {
        boolean cancelled = activeRuns.cancel(sessionId);
        return Map.of("sessionId", sessionId, "cancelled", cancelled);
    }

    private void execute(AgentRun run, String sessionId, String task, ThinkingLevel thinking) {
        try {
            AgentContext context = agentController.contextFor(sessionId, task);
            AgentResult result = mainAgent.run(withListeners(context, run, thinking));
            log.info("会话 {} 流式运行结束: stop={} 轮次={}", sessionId,
                    result.stopReason(), result.iterations());
        } catch (RuntimeException e) {
            // 一旦写过第一帧，响应头就已提交，@RestControllerAdvice 再也改不了状态码。
            // 所以异常必须在这里就地变成 error 帧，不能让它逃出去
            log.warn("会话 {} 流式运行失败: {}", sessionId, e.getMessage());
            run.fail(e);
        }
    }

    /**
     * 本次运行发给「全局监听器 + 本连接的 AgentRun」。
     *
     * <p>不能只传 AgentRun：全局里有历史落库与日志监听器，把它们挤掉会让流式对话
     * 的记录不再落库——而且不报任何错，只是下次回注历史时上下文凭空少了这些轮次。</p>
     */
    private AgentContext withListeners(AgentContext base, AgentRun run, ThinkingLevel thinking) {
        List<AgentListener> scoped = new ArrayList<>(globalListeners);
        scoped.add(run);
        return AgentContext.builder()
                .global(base.global())
                .task(base.task())
                .history(base.history())
                .sessionId(base.sessionId())
                .workspaceSessionId(base.workspaceSessionId())
                .listeners(scoped)
                .thinking(thinking)
                .build();
    }

    private static ThinkingLevel parseThinking(String raw) {
        if (raw == null || raw.isBlank()) {
            // 缺省给 NONE（显式关闭）而不是 null（不下发）：本接口的契约是「由调用方
            // 决定是否开启深度思考」，所以默认必须是关。返回 null 只是让模型按自己的
            // 默认走，而推理模型的默认是开——那样「缺省即关闭」这句注释就是假的，
            // 用户还会遇到「思考吃光 max_tokens、content 返回空串」这种无声故障
            return ThinkingLevel.NONE;
        }
        try {
            return ThinkingLevel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 不认识的档位按「关闭」处理而不是报错：思考只是可选增强，
            // 为一个拼写问题让整轮对话失败不划算
            return ThinkingLevel.NONE;
        }
    }
}
