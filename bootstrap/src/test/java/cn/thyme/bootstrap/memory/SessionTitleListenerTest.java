package cn.thyme.bootstrap.memory;

import cn.thyme.agent.AgentResult;
import cn.thyme.agent.AgentStopReason;
import cn.thyme.agent.PromptTemplate;
import cn.thyme.agent.event.AgentEvent;
import cn.thyme.llm.model.LlmRole;
import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.InMemorySessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTitleListenerTest {

    private static final PromptTemplate PROMPT = PromptTemplate.fromClasspath("prompts/title.md");

    /** 只记录 submit 是否被调用；不关心线程池与模型。 */
    private static class RecordingService extends SessionTitleService {
        final List<String> submitted = new ArrayList<>();

        RecordingService() {
            super(new InMemorySessionStore(),
                    new ConversationHistoryListener(new InMemoryMemoryStore()),
                    new TitleGenerator((request, listener) -> null, "fake", PROMPT),
                    null, true);
        }

        @Override
        public void submit(String sessionId, AgentResult result) {
            submitted.add(sessionId + "/" + result.stopReason());
        }
    }

    /** 从 submit 里抛指定的错误，用来验证监听器的兜底。 */
    private static final class ExplodingService extends RecordingService {
        private final Throwable failure;

        ExplodingService(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void submit(String sessionId, AgentResult result) {
            sneakyThrow(failure);
        }
    }

    /** 绕过受检异常声明把任意 Throwable 抛出来（含 Error）。 */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static AgentEvent.Finished finished(String sessionId, AgentStopReason reason) {
        AgentResult result = reason == AgentStopReason.COMPLETED
                ? AgentResult.completed("答", 1, 10, sessionId)
                : AgentResult.truncated(1, 10, reason, sessionId);
        return new AgentEvent.Finished(sessionId, Instant.now(), result);
    }

    @Test
    @DisplayName("只认 Finished：其余事件一律不触发")
    void ignoresNonFinishedEvents() {
        RecordingService service = new RecordingService();
        SessionTitleListener listener = new SessionTitleListener(service);

        listener.onEvent(new AgentEvent.Started("s1", Instant.now(), "任务"));
        listener.onEvent(new AgentEvent.Message("s1", Instant.now(), LlmRole.USER, "提问"));
        listener.onEvent(new AgentEvent.Message("s1", Instant.now(), LlmRole.ASSISTANT, "回答"));
        listener.onEvent(new AgentEvent.Delta("s1", Instant.now(), "增", false));
        listener.onEvent(new AgentEvent.ToolCall("s1", Instant.now(), "c1", "read_file", java.util.Map.of()));
        listener.onEvent(new AgentEvent.ToolResult("s1", Instant.now(), "c1", "read_file", "ok", true));
        System.out.println("  >> 六种非 Finished 事件 -> submit 调用次数 " + service.submitted.size());

        assertEquals(List.of(), service.submitted, "非 Finished 事件不该触发标题生成");
    }

    @Test
    @DisplayName("Finished 原样透传给 service：sessionId 与 stopReason 都要带上")
    void forwardsFinished() {
        RecordingService service = new RecordingService();
        SessionTitleListener listener = new SessionTitleListener(service);

        listener.onEvent(finished("s1", AgentStopReason.CANCELLED));
        System.out.println("  >> 透传参数 " + service.submitted);

        assertEquals(List.of("s1/CANCELLED"), service.submitted);
    }

    @Test
    @DisplayName("service 抛 Exception 时必须就地吞掉")
    void swallowsExceptionFromService() {
        // Finished 由 DefaultAgent.finish() 在 catch (AgentCancelledException) 块里同步发出，
        // 这里抛出去会直接穿过 run() 的「不外抛」契约，调用方与客户端都看不见
        SessionTitleListener listener = new SessionTitleListener(
                new ExplodingService(new IllegalStateException("提交炸了")));

        assertDoesNotThrow(() -> listener.onEvent(finished("s1", AgentStopReason.CANCELLED)));
        System.out.println("  >> IllegalStateException 被就地吞掉");
    }

    /** 一个自定义 Error：用 OutOfMemoryError 之类的真家伙会在测试里激活动态 agent 加载之类的边角路径。 */
    private static final class FakeError extends Error {
        FakeError(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("service 抛 Error 时也要吞掉（catch Throwable 而非 Exception）")
    void swallowsErrorToo() {
        SessionTitleListener listener = new SessionTitleListener(
                new ExplodingService(new FakeError("模拟 Error")));

        assertDoesNotThrow(() -> listener.onEvent(finished("s1", AgentStopReason.COMPLETED)));
        System.out.println("  >> Error 被就地吞掉");
    }
}
