package cn.thyme.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-17T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
    }

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    private static void printTitles(String label, List<Session> sessions) {
        System.out.printf("  >> [%s] %s%n", label,
                sessions.stream().map(s -> s.id() + "=" + s.title() + "/" + s.titleSource().wire()).toList());
    }

    @Test
    @DisplayName("首次创建返回 true，可读取")
    void createsAndGets() {
        boolean created = store.createIfAbsent("s1", "帮我看看日志", TitleSource.AUTO, T0);
        print("createIfAbsent(s1) -> " + created);

        assertEquals(true, created);
        Session session = store.get("s1").orElseThrow();
        assertEquals("帮我看看日志", session.title());
        assertEquals(TitleSource.AUTO, session.titleSource());
        assertEquals(T0, session.createdAt());
        assertEquals(T0, session.updatedAt());
        print("get(s1) -> " + session.title() + " / " + session.titleSource());
    }

    @Test
    @DisplayName("重复创建不覆盖已有标题与来源")
    void createIfAbsentDoesNotOverwrite() {
        store.createIfAbsent("s1", "原标题", TitleSource.AUTO, T0);
        // 用户改名之后，第二轮对话开始时又调了一次 createIfAbsent
        store.rename("s1", "我自己起的名字", T1);

        boolean created = store.createIfAbsent("s1", "第二轮提问截断", TitleSource.AUTO, T1);
        print("第二次 createIfAbsent -> " + created);

        assertFalse(created);
        Session session = store.get("s1").orElseThrow();
        assertEquals("我自己起的名字", session.title());
        assertEquals(TitleSource.USER, session.titleSource());
        assertEquals(T0, session.createdAt(), "创建时刻不得被第二次调用推进");
    }

    @Test
    @DisplayName("列表按 updatedAt 倒序（最新的在前）")
    void listsByUpdatedAtDesc() {
        store.createIfAbsent("old", "旧会话", TitleSource.AUTO, T0);
        store.createIfAbsent("new", "新会话", TitleSource.AUTO, T1);

        List<Session> sessions = store.list();
        printTitles("list", sessions);

        assertEquals(List.of("new", "old"), sessions.stream().map(Session::id).toList());
    }

    @Test
    @DisplayName("时间戳相同时按 id 倒序，顺序稳定")
    void listOrderIsStableForSameTimestamp() {
        store.createIfAbsent("a", "A", TitleSource.AUTO, T0);
        store.createIfAbsent("b", "B", TitleSource.AUTO, T0);
        store.createIfAbsent("c", "C", TitleSource.AUTO, T0);

        List<Session> sessions = store.list();
        printTitles("list(同一时刻)", sessions);

        // 无兜底排序键的话，同毫秒的三个会话在两次请求间可能互换位置，
        // 列表看起来会在刷新时自己重排
        assertEquals(List.of("c", "b", "a"), sessions.stream().map(Session::id).toList());
        assertEquals(List.of("c", "b", "a"), store.list().stream().map(Session::id).toList());
    }

    @Test
    @DisplayName("改名写入 USER 来源并推进 updatedAt")
    void renamesToUserSource() {
        store.createIfAbsent("s1", "占位", TitleSource.AUTO, T0);

        boolean renamed = store.rename("s1", "用户改的", T1);
        print("rename(s1) -> " + renamed);

        assertTrue(renamed);
        Session session = store.get("s1").orElseThrow();
        assertEquals("用户改的", session.title());
        assertEquals(TitleSource.USER, session.titleSource());
        assertEquals(T1, session.updatedAt());
        assertEquals(T0, session.createdAt());
    }

    @Test
    @DisplayName("改名不存在的会话返回 false")
    void renameMissingReturnsFalse() {
        assertFalse(store.rename("nope", "标题", T1));
        print("rename(不存在的会话) -> false");
    }

    @Test
    @DisplayName("标题改写只在 AUTO 上生效")
    void updateTitleIfAutoOnlyOnAuto() {
        store.createIfAbsent("s1", "占位标题", TitleSource.AUTO, T0);

        boolean updated = store.updateTitleIfAuto("s1", "模型生成的标题", TitleSource.GENERATED, T1);
        print("updateTitleIfAuto(AUTO) -> " + updated);

        assertTrue(updated);
        Session session = store.get("s1").orElseThrow();
        assertEquals("模型生成的标题", session.title());
        assertEquals(TitleSource.GENERATED, session.titleSource());
    }

    @Test
    @DisplayName("第二次改写返回 false 且不覆盖")
    void secondRewriteIsRejected() {
        store.createIfAbsent("s1", "占位标题", TitleSource.AUTO, T0);
        store.updateTitleIfAuto("s1", "第一次生成的标题", TitleSource.GENERATED, T1);

        boolean updated = store.updateTitleIfAuto("s1", "第二次生成的标题", TitleSource.GENERATED, T1);
        print("第二次 updateTitleIfAuto -> " + updated);

        assertFalse(updated);
        assertEquals("第一次生成的标题", store.get("s1").orElseThrow().title());
    }

    @Test
    @DisplayName("用户改名后改写被拒绝")
    void rewriteIsRejectedAfterUserRename() {
        store.createIfAbsent("s1", "占位标题", TitleSource.AUTO, T0);
        store.rename("s1", "用户改的标题", T1);

        boolean updated = store.updateTitleIfAuto("s1", "模型生成的标题", TitleSource.GENERATED, T1);
        print("改名后 updateTitleIfAuto -> " + updated);

        assertFalse(updated);
        Session session = store.get("s1").orElseThrow();
        assertEquals("用户改的标题", session.title());
        assertEquals(TitleSource.USER, session.titleSource());
    }

    @Test
    @DisplayName("改写不存在的会话返回 false")
    void updateTitleIfAutoMissingReturnsFalse() {
        assertFalse(store.updateTitleIfAuto("nope", "标题", TitleSource.GENERATED, T1));
        print("updateTitleIfAuto(不存在的会话) -> false");
    }

    @Test
    @DisplayName("删除命中返回 true，再删返回 false")
    void deletesOnce() {
        store.createIfAbsent("s1", "会话", TitleSource.AUTO, T0);

        assertTrue(store.delete("s1"));
        assertFalse(store.delete("s1"));
        assertTrue(store.get("s1").isEmpty());
        print("delete(s1) -> true / 再删 -> false");
    }

    @Test
    @DisplayName("Session 归一化 null 标题与 null 来源")
    void sessionNormalizesNulls() {
        Session session = new Session("s1", null, null, null, null);
        print("new Session(s1, null, null, null, null) -> " + session.title() + " / " + session.titleSource());

        assertEquals(SessionTitle.DEFAULT_TITLE, session.title());
        assertEquals(TitleSource.AUTO, session.titleSource());
    }

    @Test
    @DisplayName("会话 id 为空时拒绝构造")
    void sessionRejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new Session("  ", "标题", TitleSource.AUTO, T0, T0));
        print("new Session(空白 id) -> IllegalArgumentException");
    }
}
