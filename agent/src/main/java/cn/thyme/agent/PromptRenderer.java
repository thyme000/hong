package cn.thyme.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词渲染器：加载模板（文件、classpath 或内联）并填充占位符。
 * {@code {{var}}} 为必须（缺失抛异常），{@code {{var?}}} 为可选（缺失填空）。
 */
public class PromptRenderer {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)(\\?)?\\}\\}");

    public String render(PromptTemplate template, Map<String, Object> variables) {
        return fill(load(template), variables);
    }

    private String load(PromptTemplate template) {
        return switch (template.type()) {
            case INLINE -> template.source();
            case FILE -> readFile(template.source());
            case CLASSPATH -> readClasspath(template.source());
        };
    }

    private String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new PromptRenderException("读取提示词模板失败: " + path, e);
        }
    }

    private String readClasspath(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new PromptRenderException("classpath 中找不到提示词模板: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PromptRenderException("读取提示词模板失败: " + path, e);
        }
    }

    private String fill(String content, Map<String, Object> variables) {
        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean optional = matcher.group(2) != null;
            Object value = variables.get(name);
            if (value == null) {
                if (optional) {
                    matcher.appendReplacement(sb, "");
                } else {
                    throw new PromptRenderException("缺少必须的占位符变量: " + name);
                }
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
