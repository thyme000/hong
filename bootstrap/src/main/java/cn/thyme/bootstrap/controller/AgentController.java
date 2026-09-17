package cn.thyme.bootstrap.controller;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.bootstrap.memory.ConversationHistoryListener;
import cn.thyme.bootstrap.memory.HistoryRenderer;
import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.bootstrap.workspace.WorkspaceException;
import cn.thyme.bootstrap.workspace.WorkspaceFile;
import cn.thyme.memory.MemoryRecord;
import cn.thyme.memory.Session;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.SessionTitle;
import cn.thyme.memory.TitleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 主 agent 与文件沙箱的 REST 入口。
 *
 * <p>会话生命周期：{@link #createSession()} 建会话拿到 id → 前端保存并在此后
 * 每次对话与上传时带上 → 服务端据此回注历史、定位沙箱。</p>
 *
 * <p><b>建会话不落盘</b>：{@link #createSession()} 只返回一个 UUID，元信息行由
 * {@link #contextFor} 在该会话第一次真正提交任务时惰性建立。因此「建了 N 个会话
 * 就该在列表里看到 N 条」不成立——前端的乐观 UI 不能在创建时就插行。</p>
 *
 * <p>标题的 {@code titleSource} 三态（{@code auto}/{@code generated}/{@code user}）：
 * 占位标题是 {@code auto}，模型的改写只在 {@code auto} 上成功，用户 {@code PATCH}
 * 过之后即 {@code user}、不再被改写覆盖。</p>
 *
 * <p><b>不转义标题</b>：本接口返回 JSON，服务端做 HTML 转义会与前端的转义叠加成
 * 双重转义。标题属不可信文本这一点是前端契约。</p>
 */
@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final Agent mainAgent;
    private final ConversationHistoryListener history;
    private final HistoryRenderer historyRenderer;
    private final SessionWorkspace workspace;
    private final SessionStore sessions;

    public AgentController(Agent mainAgent, ConversationHistoryListener history,
                           HistoryRenderer historyRenderer, SessionWorkspace workspace,
                           SessionStore sessions) {
        this.mainAgent = mainAgent;
        this.history = history;
        this.historyRenderer = historyRenderer;
        this.workspace = workspace;
        this.sessions = sessions;
    }

    /** 新建一个会话，返回其 id。不落盘，首次上传文件时才创建会话目录。 */
    @PostMapping("/agent/sessions")
    public Map<String, String> createSession() {
        return Map.of("sessionId", UUID.randomUUID().toString());
    }

    /**
     * 提交一条任务。必须带 sessionId（由建会话接口获得）；
     * 服务端会回注该会话的历史，使多轮对话连续。
     */
    @PostMapping("/agent/chat")
    public AgentResult chat(@RequestBody Map<String, String> body) {
        String sessionId = requireSessionId(body);
        return mainAgent.run(contextFor(sessionId, body.get("task")));
    }

    /**
     * 组装一次运行的上下文：回注该会话历史，使多轮对话连续。
     * 同步与流式两个入口共用，避免两处各维护一份「取历史 + 建 context」的逻辑。
     */
    AgentContext contextFor(String sessionId, String task) {
        ensureSession(sessionId, task);
        List<MemoryRecord> records = history.history(sessionId);
        return AgentContext.builder()
                .task(task)
                .sessionId(sessionId)
                .history(historyRenderer.render(records))
                .build();
    }

    /**
     * 惰性建立会话元信息：某个会话第一次提交任务时才落一行，标题取首条提问的截断。
     * 「不覆盖」由 {@link SessionStore#createIfAbsent} 保证，因此后续每轮调用都是空操作，
     * 用户改过的标题不会被每轮打回占位标题。
     *
     * <p>{@code POST /agent/sessions} 仍不落盘——建会话只返回一个 id，只有当它真的
     * 被用来对话时才会出现在列表里。</p>
     *
     * <p>例外必须就地吞掉：本方法在用户请求路径上，一次瞬时 {@code SQLITE_BUSY}
     * 不能把整轮对话变成 500。代价是这次会话暂时没有列表条目——<b>不可见地降级
     * 好过整轮失败</b>。</p>
     */
    private void ensureSession(String sessionId, String task) {
        try {
            sessions.createIfAbsent(sessionId, SessionTitle.placeholder(task), TitleSource.AUTO, Instant.now());
        } catch (RuntimeException e) {
            log.warn("建立会话 {} 的元信息失败，本轮对话继续: {}", sessionId, e.getMessage());
        }
    }

    /** 校验并取出 sessionId；缺了就报同一句可操作的错。 */
    static String requireSessionId(Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少 sessionId，请先调用 POST /agent/sessions 建会话");
        }
        return sessionId;
    }

    /**
     * 列出全部会话的元信息，按最近更新倒序。
     *
     * <p><b>只列顶层会话</b>：子 agent 派生的子会话在 session 表里没有行（占位行只在
     * {@link #contextFor} 里建，那是顶层运行的入口），因此天然不出现在列表里。它的历史
     * 仍可按 id 打开——见 {@link #session(String)}。「不在列表里但打得开」是有意为之。</p>
     *
     * <p>{@code createdAt}/{@code updatedAt} 直接给 epoch 毫秒，不转 ISO：排序保证在
     * SQL 里、展示格式在前端，{@code new Date(ms)} 是 JS 消费方最不意外的形式。</p>
     */
    @GetMapping("/agent/sessions")
    public List<Map<String, Object>> sessions() {
        return sessions.list().stream().map(AgentController::toDto).toList();
    }

    /** 查看某会话的完整交互记录。 */
    @GetMapping("/agent/sessions/{sessionId}")
    public List<Map<String, Object>> session(@PathVariable String sessionId) {
        return history.history(sessionId).stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.id(),
                        "content", r.content(),
                        "metadata", r.metadata()))
                .toList();
    }

    /**
     * 会话元信息 → 响应体。
     *
     * <p>只从 {@link Session} 拼、绝不直接从 {@code SessionRow} 拼：{@code Session} 的
     * 紧凑构造器已经把 title/titleSource/时间戳的 null 归一过，而 {@code Map.of} 遇 null
     * 值会抛 NPE——归一化只发生一次，在正确的地方。</p>
     *
     * <p>{@code titleSource} 给小写线格式而非枚举名：与库里那一列逐字一致，
     * 排查时不用在两种大小写之间换算。</p>
     */
    private static Map<String, Object> toDto(Session session) {
        return Map.of(
                "id", session.id(),
                "title", session.title(),
                "titleSource", session.titleSource().wire(),
                "createdAt", session.createdAt().toEpochMilli(),
                "updatedAt", session.updatedAt().toEpochMilli());
    }

    /**
     * 用户手工改名：写 title 并标记来源为 {@code user}，此后自动改写不再覆盖它。
     *
     * <p>未知会话回 404 而不是 200——前端的乐观 UI 靠状态码决定要不要回滚，
     * 静默成功会让它显示一个服务端并不存在的标题。</p>
     */
    @PatchMapping("/agent/sessions/{sessionId}")
    public Map<String, Object> renameSession(@PathVariable String sessionId,
                                             @RequestBody Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (!sessions.rename(sessionId, title.strip(), Instant.now())) {
            throw new NoSuchElementException("会话不存在: " + sessionId);
        }
        return toDto(sessions.get(sessionId).orElseThrow());
    }

    /** 删除会话：连同其历史消息、元信息与沙箱文件一并清除。 */
    @DeleteMapping("/agent/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        int messages = history.deleteSession(sessionId);
        // 元信息可能本就不存在（会话建了但从未对话过），所以这里不报 404
        boolean sessionDeleted = sessions.delete(sessionId);
        workspace.deleteSession(sessionId);
        return Map.of("sessionId", sessionId,
                "deletedMessages", messages,
                "deletedSession", sessionDeleted);
    }

    /** 列出会话内已上传的文件。 */
    @GetMapping("/agent/sessions/{sessionId}/files")
    public List<Map<String, Object>> files(@PathVariable String sessionId) {
        return workspace.list(sessionId).stream()
                .map(f -> Map.<String, Object>of("name", f.name(), "size", f.size()))
                .toList();
    }

    /** 上传一个（或多个）文件到会话沙箱。 */
    @PostMapping("/agent/sessions/{sessionId}/files")
    public List<Map<String, Object>> upload(@PathVariable String sessionId,
                                            @RequestParam("files") List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new WorkspaceException("未选择任何文件");
        }
        return files.stream().map(file -> {
            try {
                WorkspaceFile saved = workspace.save(sessionId, file.getOriginalFilename(), file.getBytes());
                return Map.<String, Object>of("name", saved.name(), "size", saved.size());
            } catch (IOException e) {
                throw new WorkspaceException("读取上传内容失败: " + file.getOriginalFilename(), e);
            }
        }).toList();
    }

    /** 删除会话内的一个文件。 */
    @DeleteMapping("/agent/sessions/{sessionId}/files/{name}")
    public Map<String, Object> deleteFile(@PathVariable String sessionId, @PathVariable String name) {
        boolean removed = workspace.delete(sessionId, name);
        return Map.of("name", name, "deleted", removed);
    }
}
