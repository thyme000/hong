package cn.thyme.bootstrap.memory;

import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.Session;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 会话标题的自动改写：一轮对话正常结束后，用「首条提问 + 首轮回答」生成短标题。
 *
 * <p><b>三道闸</b>，任一不过就跳过：</p>
 * <ol>
 *   <li>会话元信息不存在 —— 说明这是子 agent 派生的子会话（顶层会话的占位行由
 *       {@code AgentController.contextFor} 建立），也覆盖「运行期间会话被删」</li>
 *   <li>{@code stopReason} 不是 {@link AgentStopReason#COMPLETED} —— 取消、轮次上限、
 *       预算越线都没跑完，此时生成标题会误导</li>
 *   <li>标题来源已不是 {@link TitleSource#AUTO} —— 已生成过或用户改过，省一次无用的调用</li>
 * </ol>
 *
 * <p>三道闸都是无状态的，所以「首轮被打断 → 下一轮成功再补」自然成立：被打断的
 * 轮次留下 AUTO，下一轮仍能通过。</p>
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    /** 会话历史里的角色字面量，取自 {@code ConversationHistoryListener} 的写入约定。 */
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    private final SessionStore sessions;
    private final ConversationHistoryListener history;
    private final TitleGenerator generator;
    private final ExecutorService executor;
    private final boolean enabled;

    public SessionTitleService(SessionStore sessions,
                               ConversationHistoryListener history,
                               TitleGenerator generator,
                               @Qualifier("sessionTitleExecutor") ExecutorService executor,
                               @Value("${agent.title.enabled:true}") boolean enabled) {
        this.sessions = sessions;
        this.history = history;
        this.generator = generator;
        this.executor = executor;
        this.enabled = enabled;
    }

    /**
     * 由 {@link SessionTitleListener} 在 {@code Finished} 事件上调用。
     *
     * <p>这里只做零成本的判断然后丢给线程池：本方法跑在<b>用户请求线程</b>上
     * （事件分发是同步的），而挑会话、读历史、调模型全是 I/O。放到异步之后，
     * 一次 best-effort 的标题生成不会给用户请求添尾延迟。</p>
     */
    public void submit(String sessionId, AgentResult result) {
        if (!enabled || sessionId == null || result == null) {
            return;
        }
        if (result.stopReason() != AgentStopReason.COMPLETED) {
            return;
        }
        try {
            executor.execute(() -> rewrite(sessionId));
        } catch (RejectedExecutionException e) {
            // 当前策略是 DiscardPolicy（不抛），这里只是兜底：将来换成 AbortPolicy
            // 之类的策略时，别让拒绝顺着事件分发穿进 DefaultAgent
            log.debug("会话 {} 的标题改写被线程池拒绝: {}", sessionId, e.getMessage());
        }
    }

    private void rewrite(String sessionId) {
        try {
            Optional<Session> current = sessions.get(sessionId);
            if (current.isEmpty()) {
                // 子会话（表里没有行），或运行期间会话被删——都不是错误
                return;
            }
            if (current.get().titleSource() != TitleSource.AUTO) {
                return;
            }
            String[] exchange = firstExchange(sessionId);
            if (exchange == null) {
                log.debug("会话 {} 没有用户消息，跳过标题生成", sessionId);
                return;
            }
            String title = generator.generate(exchange[0], exchange[1]);
            if (title == null) {
                // 生成失败或清洗后为空：保留占位标题，来源不动，下一轮还能再试
                return;
            }
            if (sessions.updateTitleIfAuto(sessionId, title, TitleSource.GENERATED, Instant.now())) {
                log.info("会话 {} 的标题已生成: {}", sessionId, title);
            }
        } catch (RuntimeException e) {
            // 改写是 best-effort：任何失败都不该影响已经返回给用户的那一轮
            log.warn("会话 {} 的标题改写失败: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 取首条用户消息与随后的首条助手回复。
     *
     * <p><b>输入取自落库的历史而不是事件流</b>：历史已按 seq 升序、内容就是模型
     * 原文，且此刻必然已写完（工具消息与最终回答都只在轮次结束时落库）。</p>
     *
     * <p>依赖一个隐式不变式：工具记录的 role 是 {@code TOOL_CALL}/{@code TOOL_RESULT}，
     * 与 {@code LlmRole} 的取值不重叠，所以按 {@code "USER"} 过滤不会误命中工具结果。
     * 这是字符串字段上的约定，有测试钉住。</p>
     *
     * @return {提问, 回答}；没有用户消息时返回 null
     */
    String[] firstExchange(String sessionId) {
        String question = null;
        String answer = null;
        for (MemoryRecord record : history.history(sessionId)) {
            String role = String.valueOf(record.metadata().get("role"));
            if (question == null && ROLE_USER.equals(role)) {
                question = record.content();
            } else if (question != null && answer == null && ROLE_ASSISTANT.equals(role)) {
                answer = record.content();
            }
            if (question != null && answer != null) {
                break;
            }
        }
        if (question == null) {
            return null;
        }
        // 回答可能缺失（理论上不会，因为闸二要求 COMPLETED）：拿提问单独生成，
        // 总比不生成好，提示词里那一栏留空即可
        return new String[]{question, answer == null ? "" : answer};
    }
}
