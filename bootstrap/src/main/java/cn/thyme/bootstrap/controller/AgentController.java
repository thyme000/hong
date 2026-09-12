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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主 agent 与文件沙箱的 REST 入口。
 *
 * <p>会话生命周期：{@link #createSession()} 建会话拿到 id → 前端保存并在此后
 * 每次对话与上传时带上 → 服务端据此回注历史、定位沙箱。</p>
 */
@RestController
public class AgentController {

    private final Agent mainAgent;
    private final ConversationHistoryListener history;
    private final HistoryRenderer historyRenderer;
    private final SessionWorkspace workspace;

    public AgentController(Agent mainAgent, ConversationHistoryListener history,
                           HistoryRenderer historyRenderer, SessionWorkspace workspace) {
        this.mainAgent = mainAgent;
        this.history = history;
        this.historyRenderer = historyRenderer;
        this.workspace = workspace;
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
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少 sessionId，请先调用 POST /agent/sessions 建会话");
        }

        List<MemoryRecord> records = history.history(sessionId);
        AgentContext context = AgentContext.builder()
                .task(body.get("task"))
                .sessionId(sessionId)
                .history(historyRenderer.render(records))
                .build();
        return mainAgent.run(context);
    }

    /** 列出全部会话 id。 */
    @GetMapping("/agent/sessions")
    public List<String> sessions() {
        return history.sessions();
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

    /** 删除会话：连同其历史消息与沙箱文件一并清除。 */
    @DeleteMapping("/agent/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        int messages = history.deleteSession(sessionId);
        workspace.deleteSession(sessionId);
        return Map.of("sessionId", sessionId, "deletedMessages", messages);
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
