package cn.thyme.agent;

import java.util.Objects;

/**
 * 提示词模板：来源可以是外部文件、classpath 或内联字符串，统一抽象。
 * 模板内容含 {@code {{var}}}（必须）与 {@code {{var?}}}（可选）占位符，
 * 由 {@link PromptRenderer} 运行时填充。
 */
public final class PromptTemplate {

    public enum SourceType {
        INLINE,
        FILE,
        CLASSPATH
    }

    private final String source;
    private final SourceType type;

    private PromptTemplate(String source, SourceType type) {
        this.source = source;
        this.type = type;
    }

    /** 从外部文件加载模板。 */
    public static PromptTemplate fromFile(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return new PromptTemplate(path, SourceType.FILE);
    }

    /** 从 classpath 加载模板。 */
    public static PromptTemplate fromClasspath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return new PromptTemplate(path, SourceType.CLASSPATH);
    }

    /** 内联模板字符串。 */
    public static PromptTemplate inline(String template) {
        Objects.requireNonNull(template, "template must not be null");
        return new PromptTemplate(template, SourceType.INLINE);
    }

    public String source() {
        return source;
    }

    public SourceType type() {
        return type;
    }
}
