package cn.thyme.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTitleTest {

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    @Test
    @DisplayName("短提问原样作为占位标题")
    void shortMessageIsKept() {
        String title = SessionTitle.placeholder("帮我看看日志");
        print("placeholder(短提问) -> " + title);

        assertEquals("帮我看看日志", title);
    }

    @Test
    @DisplayName("超长提问按 20 字截断并加省略号")
    void longMessageIsTruncated() {
        String raw = "帮我看看这个服务为什么在高峰期总是出现连接超时的问题";
        String title = SessionTitle.placeholder(raw);
        print("placeholder(超长提问) -> " + title);

        // 20 个码点 + 单字符省略号
        assertEquals(21, title.codePointCount(0, title.length()));
        assertTrue(title.startsWith("帮我看看这个服务为什么在高峰期总是出现连接超时的问题".substring(0, 20)));
        assertTrue(title.endsWith("…"));
        print("实际标题 -> " + title);
    }

    @Test
    @DisplayName("截断不劈开代理对（emoji / 扩展字符）")
    void truncateDoesNotSplitSurrogatePairs() {
        // 每个 emoji 占 2 个 UTF-16 单位、1 个码点：按 length() 切会留下孤立代理，
        // 该字符串无法被 JSON 序列化
        String emojis = "🙂".repeat(30);
        String title = SessionTitle.truncate(emojis, 20);
        print("truncate(30 个 emoji, 20) -> " + title);

        assertEquals(21, title.codePointCount(0, title.length()));
        assertEquals(41, title.length(), "20 个 emoji 共 40 个 UTF-16 单位 + 1 个省略号");
        assertTrue(title.endsWith("…"));
        // 逐字符检查没有孤立代理
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(Character.isLowSurrogate(title.charAt(i + 1)), "高位代理后必须紧跟低位代理");
                i++;
            } else {
                assertTrue(!Character.isLowSurrogate(c), "不得出现孤立低位代理");
            }
        }
    }

    @Test
    @DisplayName("恰好等于上限时不截断")
    void exactLimitIsUntouched() {
        String text = "一二三四五六七八九十一二三四五六七八九十";
        String title = SessionTitle.truncate(text, 20);
        print("truncate(恰好 20 字) -> " + title);

        assertEquals(text, title);
    }

    @Test
    @DisplayName("空白折叠为单空格并去首尾")
    void collapsesWhitespace() {
        String title = SessionTitle.placeholder("  第一行\n\n第二行\t第三行  ");
        print("placeholder(多行空白) -> " + title);

        assertEquals("第一行 第二行 第三行", title);
    }

    @Test
    @DisplayName("空提问回落到默认标题")
    void blankMessageFallsBackToDefault() {
        print("placeholder(null) -> " + SessionTitle.placeholder(null));
        print("placeholder(\"   \") -> " + SessionTitle.placeholder("   "));
        print("placeholder(\"\\n\\n\") -> " + SessionTitle.placeholder("\n\n"));

        assertEquals(SessionTitle.DEFAULT_TITLE, SessionTitle.placeholder(null));
        assertEquals(SessionTitle.DEFAULT_TITLE, SessionTitle.placeholder("   "));
        assertEquals(SessionTitle.DEFAULT_TITLE, SessionTitle.placeholder("\n\n"));
    }

    @Test
    @DisplayName("TitleSource.parse 对未知取值回落 AUTO 而不抛")
    void titleSourceParseFallsBackToAuto() {
        print("parse(\"GENERATED\") -> " + TitleSource.parse("GENERATED"));
        print("parse(\"user\") -> " + TitleSource.parse("user"));
        print("parse(\"future_value\") -> " + TitleSource.parse("future_value"));
        print("parse(null) -> " + TitleSource.parse(null));

        assertEquals(TitleSource.GENERATED, TitleSource.parse("GENERATED"));
        assertEquals(TitleSource.USER, TitleSource.parse("user"));
        assertEquals(TitleSource.AUTO, TitleSource.parse("  AUTO  "));
        // 脏行不得让整个会话列表变成 500
        assertEquals(TitleSource.AUTO, TitleSource.parse("future_value"));
        assertEquals(TitleSource.AUTO, TitleSource.parse(null));
        assertEquals(TitleSource.AUTO, TitleSource.parse(""));
    }

    @Test
    @DisplayName("TitleSource 线格式为小写")
    void titleSourceWireIsLowercase() {
        print("AUTO.wire() -> " + TitleSource.AUTO.wire());
        print("GENERATED.wire() -> " + TitleSource.GENERATED.wire());
        print("USER.wire() -> " + TitleSource.USER.wire());

        assertEquals("auto", TitleSource.AUTO.wire());
        assertEquals("generated", TitleSource.GENERATED.wire());
        assertEquals("user", TitleSource.USER.wire());
    }
}
