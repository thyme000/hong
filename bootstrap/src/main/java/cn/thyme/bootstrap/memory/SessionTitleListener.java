package cn.thyme.bootstrap.memory;

import cn.thyme.agent.event.AgentEvent;
import cn.thyme.agent.event.AgentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话标题的触发点：只在一次运行正常收尾时通知 {@link SessionTitleService}。
 *
 * <p>作为全局监听器注册即可同时覆盖同步与流式两条路——{@code AgentAutoConfiguration}
 * 的事件发布器收容器内全部 {@link AgentListener}。</p>
 */
@Component
public class SessionTitleListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleListener.class);

    private final SessionTitleService service;

    public SessionTitleListener(SessionTitleService service) {
        this.service = service;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (!(event instanceof AgentEvent.Finished finished)) {
            return;
        }
        try {
            service.submit(finished.sessionId(), finished.result());
        } catch (Throwable t) {
            // 必须 catch Throwable，不是防御性编程：Finished 由 DefaultAgent.finish()
            // 在 catch (AgentCancelledException) 块里同步发出，这里抛出去会直接穿过
            // run() 的「不外抛」契约，调用方与客户端都看不见，异常被线程池咽掉
            log.warn("会话 {} 的标题改写提交失败: {}", finished.sessionId(), t.toString());
        }
    }
}
