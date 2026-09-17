package cn.thyme.bootstrap.memory;

import cn.thyme.agent.PromptRenderer;
import cn.thyme.agent.PromptTemplate;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.memory.SessionTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 会话标题生成器：用一次轻量的 LLM 调用把「首条提问 + 首轮回答」压成短标题。
 *
 * <p><b>直接调 {@link LlmClient#chat}，不进 agent loop</b>：走 agent 的话，这次
 * 合成轮次产生的事件会喂给 {@code ConversationHistoryListener}，把一次「不存在的
 * 对话」永久写进该会话自己的历史里。这里不挂工具、不计预算。</p>
 */
public class TitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerator.class);

    /** 注入提示词的两段文本各自的字符上限，防止一条超长消息变成一次超长 prompt。 */
    static final int INPUT_LIMIT = 1000;

    /** 生成结果的字数上限（按码点）。模型偶尔会不听「不超过 20 字」。 */
    static final int OUTPUT_LIMIT = 60;

    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 64;

    /** 兜底调用必须用的模型：一次调用只允许一个候选。 */
    private static final String FALLBACK_MODEL = "unknown";

    private final LlmClient llmClient;
    private final String model;
    private final PromptRenderer renderer = new PromptRenderer();
    private final PromptTemplate prompt;

    public TitleGenerator(LlmClient llmClient, String model, PromptTemplate prompt) {
        this.llmClient = llmClient;
        this.model = (model == null || model.isBlank()) ? FALLBACK_MODEL : model;
        this.prompt = prompt;
    }

    /**
     * 生成标题。
     *
     * @return 清洗后的标题；<b>生成失败或清洗后为空时返回 {@code null}</b>，
     * 由调用方决定保留原占位标题。绝不能用空串覆盖——那会白白烧掉该会话唯一一次
     * 改写机会（来源会变成 GENERATED，此后不再改写）
     */
    public String generate(String question, String answer) {
        String rendered;
        try {
            rendered = renderer.render(prompt, Map.of(
                    "question", clip(question),
                    "answer", clip(answer)));
        } catch (RuntimeException e) {
            log.warn("标题提示词渲染失败: {}", e.getMessage());
            return null;
        }

        LlmResponse response;
        try {
            // 标题不需要推理，也不该用 provider 的 0.7——那会让同一次对话的标题漂移
            response = llmClient.chat(LlmRequest.builder()
                    .model(model)
                    .addSystemMessage(rendered)
                    .addUserMessage("请生成标题。")
                    .temperature(TEMPERATURE)
                    .maxTokens(MAX_TOKENS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("标题生成调用失败: {}", e.getMessage());
            return null;
        }

        return clean(response == null ? null : response.content());
    }

    /**
     * 清洗模型输出。顺序是固定的，且每一步都不可省：
     * 去控制字符 → 去前缀与包裹引号 → 折叠空白 → 截断。
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        // 先剥掉控制字符再用别的规则处理：  /   是行分隔符而非空白，
        // isWhitespace 不认它们，留着会混进标题
        String text = stripControl(raw);
        text = stripDecoration(text);
        text = SessionTitle.collapse(text);
        if (text.isEmpty()) {
            return null;
        }
        return SessionTitle.truncate(text, OUTPUT_LIMIT);
    }

    /** 去掉控制字符（保留其他一切可打印字符）。 */
    private static String stripControl(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(cp -> !Character.isISOControl(cp) && cp != 0x2028 && cp != 0x2029)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** 去掉「标题：」这类前缀、包裹引号与 Markdown 强调标记。 */
    private static String stripDecoration(String text) {
        String result = text.strip();
        // 模型时常回一句「标题：xxx」
        for (String prefix : new String[]{"标题:", "标题：", "Title:", "title:"}) {
            if (result.startsWith(prefix)) {
                result = result.substring(prefix.length()).strip();
                break;
            }
        }
        // 包裹引号（中英文都要管）
        result = unwrap(result, '"', '"');
        result = unwrap(result, '\'', '\'');
        result = unwrap(result, '“', '”');
        result = unwrap(result, '「', '」');
        result = unwrap(result, '《', '》');
        // Markdown 强调标记按「连续的一段」剥：`**x**` 单次 unwrap 只会削掉一层，
        // 剩下 `*x*`，而模型很爱把标题加粗
        result = stripRuns(result, '*');
        result = stripRuns(result, '`');
        return result.strip();
    }

    /** 去掉首尾连续的同一个字符（用于 `**粗体**`、`` `代码` `` 这类标记）。 */
    private static String stripRuns(String text, char marker) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == marker) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == marker) {
            end--;
        }
        return text.substring(start, end);
    }

    private static String unwrap(String text, char open, char close) {
        if (text.length() >= 2 && text.charAt(0) == open && text.charAt(text.length() - 1) == close) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 按码点截断到输入上限，避免超长消息撑爆 prompt。 */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return SessionTitle.truncate(text, INPUT_LIMIT);
    }
}
