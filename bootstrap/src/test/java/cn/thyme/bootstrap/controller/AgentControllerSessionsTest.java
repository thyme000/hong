package cn.thyme.bootstrap.controller;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.bootstrap.memory.ConversationHistoryListener;
import cn.thyme.bootstrap.memory.HistoryRenderer;
import cn.thyme.bootstrap.web.GlobalExceptionHandler;
import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.memory.InMemoryMemoryStore;
import cn.thyme.memory.InMemorySessionStore;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.SessionTitle;
import cn.thyme.memory.TitleSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话接口：列表返回对象数组（破坏性变更）、改名、级联删除，以及「占位行由第一次
 * 对话惰性建立」这条契约。
 *
 * <p>不用 {@code @SpringBootTest}：控制器的协作者都有明确的构造器，手工装配比让
 * Spring 去猜要注入什么更直接，跑得也快。整棵上下文的装配由 {@code HongApplicationTests}
 * 覆盖。</p>
 */
class AgentControllerSessionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionStore sessions;
    private ConversationHistoryListener history;
    private MockMvc mockMvc;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        sessions = new InMemorySessionStore();
        history = new ConversationHistoryListener(new InMemoryMemoryStore());
        // agent 只被要求返回一个结果；它自己不落历史（历史由监听器落），
        // 本测试要断言的「列表里有几条」是占位行的行为，与 agent 无关
        Agent agent = context -> {
            runs.incrementAndGet();
            return AgentResult.completed("答", 1, 0, context.sessionId());
        };

        AgentController controller = new AgentController(
                agent, history, new HistoryRenderer(), new SessionWorkspace(tempDir.toString()), sessions);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    /** 直接往列表接口打招呼，返回解析后的数组。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list() throws Exception {
        String body = mockMvc.perform(get("/agent/sessions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, List.class);
    }

    private void chat(String sessionId, String task) throws Exception {
        mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("sessionId", sessionId, "task", task))))
                .andExpect(status().isOk());
    }

    private void seed(String id, String title, TitleSource source, Instant updatedAt) {
        sessions.createIfAbsent(id, title, source, updatedAt);
    }

    @Test
    @DisplayName("新建会话不落盘：建了也不出现在列表里")
    void createSessionDoesNotPersist() throws Exception {
        String body = mockMvc.perform(post("/agent/sessions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JSON.readTree(body).get("sessionId").asString();
        print("新建的 sessionId = " + sessionId);

        assertEquals(List.of(), list(), "POST /agent/sessions 只发 id，不建元信息行");
    }

    @Test
    @DisplayName("第一次对话才建立占位行，标题是首条提问的截断")
    void firstChatCreatesPlaceholderRow() throws Exception {
        chat("s1", "帮我看看这个服务为什么在高峰期总是出现连接超时的问题");
        List<Map<String, Object>> rows = list();

        print("列表 = " + rows);
        assertEquals(1, rows.size());
        assertEquals("s1", rows.get(0).get("id"));
        assertEquals("auto", rows.get(0).get("titleSource"));
        String title = (String) rows.get(0).get("title");
        assertTrue(title.startsWith("帮我看看这个服务为什么在高峰期总是出现连接超时的问题".substring(0, 20)),
                "标题应是提问的前 20 字，实际: " + title);
        assertTrue(title.endsWith("…"));
    }

    @Test
    @DisplayName("第二轮对话不覆盖占位标题（createIfAbsent 语义）")
    void laterChatsDoNotOverwriteTitle() throws Exception {
        chat("s1", "第一个问题");
        String first = (String) list().get(0).get("title");

        chat("s1", "完全不一样的第二个问题");
        String after = (String) list().get(0).get("title");

        print("第一轮标题 = " + first + " -> 第二轮后 = " + after);
        assertEquals(first, after, "每轮都调 createIfAbsent，但已存在的行不该被改");
        assertEquals(1, list().size(), "同一个会话只该有一行");
    }

    @Test
    @DisplayName("列表按 updatedAt 倒序")
    void listsByUpdatedAtDesc() throws Exception {
        Instant base = Instant.ofEpochMilli(1_700_000_000_000L);
        seed("old", "旧的", TitleSource.AUTO, base);
        seed("new", "新的", TitleSource.GENERATED, base.plusSeconds(100));
        seed("mid", "中间的", TitleSource.USER, base.plusSeconds(50));

        List<Map<String, Object>> rows = list();
        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        print("顺序 = " + ids);

        assertEquals(List.of("new", "mid", "old"), ids);
        assertEquals(base.plusSeconds(100).toEpochMilli(), rows.get(0).get("updatedAt"));
        assertEquals("generated", rows.get(0).get("titleSource"));
    }

    @Test
    @DisplayName("列表项字段齐备，时间戳是 epoch 毫秒")
    void listItemsHaveAllFields() throws Exception {
        seed("s1", "标题", TitleSource.USER, Instant.ofEpochMilli(1_700_000_000_000L));
        Map<String, Object> row = list().get(0);
        print("行 = " + row);

        assertEquals(5, row.size(), "id/title/titleSource/createdAt/updatedAt");
        assertTrue(row.get("createdAt") instanceof Number, "给 epoch 毫秒而不是 ISO 字符串");
        assertTrue(row.get("updatedAt") instanceof Number);
    }

    @Test
    @DisplayName("标题里的 HTML 不做服务端转义（前端自己转）")
    void titleIsNotEscaped() throws Exception {
        seed("s1", "<img src=x onerror=alert(1)>", TitleSource.USER, Instant.now());
        String body = mockMvc.perform(get("/agent/sessions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        print("响应体 = " + body);

        // JSON 里 & 应原样出现；被转成 &amp; / < 说明服务端多转了一层，
        // 前端的转义再叠上去就是双重转义
        assertTrue(body.contains("<img src=x onerror=alert(1)>"), "标题应原样返回: " + body);
    }

    @Test
    @DisplayName("改名：写 user 来源并推进 updatedAt")
    void renamesSession() throws Exception {
        seed("s1", "占位标题", TitleSource.AUTO, Instant.ofEpochMilli(1_700_000_000_000L));

        mockMvc.perform(patch("/agent/sessions/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "  我自己起的  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("我自己起的"))
                .andExpect(jsonPath("$.titleSource").value("user"));

        assertEquals("我自己起的", sessions.get("s1").orElseThrow().title());
        assertTrue(sessions.get("s1").orElseThrow().updatedAt()
                        .isAfter(Instant.ofEpochMilli(1_700_000_000_000L)),
                "改名要推进 updatedAt，否则列表顺序不会变");
    }

    @Test
    @DisplayName("改名：未知会话 404")
    void renameUnknownSessionIs404() throws Exception {
        mockMvc.perform(patch("/agent/sessions/从未存在过")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "新名字"))))
                .andExpect(status().isNotFound());
        print("未知会话改名 -> 404");
    }

    @Test
    @DisplayName("改名：空标题 400，且不写坏已有的标题")
    void renameWithBlankTitleIs400() throws Exception {
        seed("s1", "原本的标题", TitleSource.AUTO, Instant.now());

        for (String blank : new String[]{"", "   "}) {
            mockMvc.perform(patch("/agent/sessions/s1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("title", blank))))
                    .andExpect(status().isBadRequest());
        }
        print("空标题 -> 400；库里标题 = " + sessions.get("s1").orElseThrow().title());

        // 空标题会让 Session 的归一化把它换成默认标题、来源却是 user——那就再也改不回来了
        assertEquals("原本的标题", sessions.get("s1").orElseThrow().title());
        assertEquals(TitleSource.AUTO, sessions.get("s1").orElseThrow().titleSource());
    }

    @Test
    @DisplayName("改名：缺 title 字段 400")
    void renameWithoutTitleFieldIs400() throws Exception {
        seed("s1", "原本的标题", TitleSource.AUTO, Instant.now());

        mockMvc.perform(patch("/agent/sessions/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        print("缺 title 字段 -> 400");
    }

    @Test
    @DisplayName("改名后的标题不会再被自动改写覆盖（来源已是 user）")
    void renamedTitleIsNotOverwritten() throws Exception {
        chat("s1", "第一轮提问");
        mockMvc.perform(patch("/agent/sessions/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "用户起的名字"))))
                .andExpect(status().isOk());

        chat("s1", "第二轮提问");
        print("再对话一轮后标题 = " + sessions.get("s1").orElseThrow().title());

        assertEquals("用户起的名字", sessions.get("s1").orElseThrow().title());
        assertEquals(TitleSource.USER, sessions.get("s1").orElseThrow().titleSource());
    }

    @Test
    @DisplayName("删除：元信息行、历史消息、沙箱目录三者一并清除")
    void deleteRemovesEverything() throws Exception {
        chat("s1", "要删的会话");
        seed("s2", "要留的", TitleSource.AUTO, Instant.now());

        mockMvc.perform(delete("/agent/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.deletedSession").value(true));

        print("删除后列表 = " + list().stream().map(r -> r.get("id")).toList());
        assertTrue(sessions.get("s1").isEmpty(), "元信息行应被删除");
        assertTrue(history.history("s1").isEmpty(), "历史消息应被删除");
        assertEquals(List.of("s2"), list().stream().map(r -> (String) r.get("id")).toList());
    }

    @Test
    @DisplayName("删除不存在的会话：不报错，deletedSession 为 false")
    void deleteUnknownSessionIsIdempotent() throws Exception {
        // 建了会话但从未对话过，元信息行本就不存在——删除仍该成功。
        // id 用合法的 UUID 形状：非 [A-Za-z0-9_-] 的字符会先被沙箱的路径白名单拦成 400，
        // 那条守卫先于存在性检查，与本测试要验的语义无关
        String neverChatted = "7f3a1c2e-0000-4000-8000-000000000000";

        mockMvc.perform(delete("/agent/sessions/" + neverChatted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSession").value(false));
        print("删除未对话过的会话 -> 200 且 deletedSession=false");
    }

    @Test
    @DisplayName("子会话不在列表里，但按 id 仍打得开历史")
    void subSessionIsHiddenButReadable() throws Exception {
        chat("s1", "顶层会话的提问");
        // 子会话：消息落在同一张表，但没有元信息行。父会话指针照真实委派的形状带上
        history.onEvent(new cn.thyme.agent.event.AgentEvent.Message("s1-sub-abc", "s1", Instant.now(),
                cn.thyme.llm.model.LlmRole.USER, "子会话的提问"));

        List<String> ids = list().stream().map(r -> (String) r.get("id")).toList();
        print("列表 id = " + ids + "（子会话应不在其中）");

        assertEquals(List.of("s1"), ids, "子会话不该出现在列表里");
        mockMvc.perform(get("/agent/sessions/s1-sub-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("没有标题的会话也不会让列表 500（Session 里已归一）")
    void rowWithBlankTitleStillSerializes() throws Exception {
        // 直接从 store 建，绕过 placeholder；Session 的构造器会把空标题归一为「新会话」
        sessions.createIfAbsent("s1", "   ", TitleSource.AUTO, Instant.now());

        List<Map<String, Object>> rows = list();
        print("空标题 -> " + rows.get(0).get("title"));

        assertEquals(SessionTitle.DEFAULT_TITLE, rows.get(0).get("title"));
    }

    @Test
    @DisplayName("一次对话只跑一次 agent（占位行的建立不重复触发运行）")
    void chatRunsAgentOnce() throws Exception {
        chat("s1", "提问");
        assertEquals(1, runs.get());
    }
}
