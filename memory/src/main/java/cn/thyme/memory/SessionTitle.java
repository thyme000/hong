package cn.thyme.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 会话标题的构造工具：把一段原始文本压成适合展示的短标题。
 *
 * <p>这是「任何会话都有标题」这一保证的唯一落点——占位标题由这里产生，
 * 标题改写失败时的兜底判空也以这里的结果为准。</p>
 */
public final class SessionTitle {

    /** 占位标题的字数上限（按码点计）。 */
    public static final int PLACEHOLDER_LIMIT = 20;

    /** 原始提问为空时使用的标题。 */
    public static final String DEFAULT_TITLE = "新会话";

    /** 截断标记。用单字符省略号，三个点会让结果变成 limit + 2 个字。 */
    private static final String ELLIPSIS = "…";

    /** 连续空白（含换行、制表、全角空格）。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SessionTitle() {
    }

    /**
     * 由首条提问生成占位标题：折叠空白后按 {@link #PLACEHOLDER_LIMIT} 截断。
     * 提问为空或全空白时返回 {@link #DEFAULT_TITLE}。
     */
    public static String placeholder(String rawMessage) {
        if (rawMessage == null) {
            return DEFAULT_TITLE;
        }
        String collapsed = collapse(rawMessage);
        return collapsed.isEmpty() ? DEFAULT_TITLE : truncate(collapsed, PLACEHOLDER_LIMIT);
    }

    /**
     * 按<b>码点</b>截断，超出部分以省略号收尾。
     *
     * <p>必须按码点而不是 {@code String.length()}（UTF-16 单位）切：后者会把
     * emoji 或 CJK 扩展字符的代理对劈成孤立的半个字符，JSON 序列化直接失败。</p>
     */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (limit <= 0) {
            return "";
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= limit) {
            return text;
        }
        int end = text.offsetByCodePoints(0, limit);
        return text.substring(0, end) + ELLIPSIS;
    }

    /** 折叠全部连续空白为单个空格并去掉首尾空白。 */
    public static String collapse(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
