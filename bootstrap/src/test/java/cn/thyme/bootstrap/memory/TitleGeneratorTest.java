package cn.thyme.bootstrap.memory;

import cn.thyme.agent.PromptTemplate;
import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleGeneratorTest {

    private static final PromptTemplate PROMPT =
            PromptTemplate.fromClasspath("prompts/title.md");

    private static void print(String message) {
        System.out.println("  >> " + message);
    }

    /** 记录请求并按序返回预置回复的假客户端。 */
    private static final class RecordingClient implements LlmClient {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final List<String> replies;
        private final AtomicInteger calls = new AtomicInteger();

        RecordingClient(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public LlmResponse chat(LlmRequest request, java.util.function.Consumer<cn.thyme.llm.model.LlmDelta> deltaListener) {
            requests.add(request);
            String reply = replies.get(Math.min(calls.getAndIncrement(), replies.size() - 1));
            return new LlmResponse("fake", reply, List.of(), null);
        }
    }

    private static TitleGenerator generator(LlmClient client) {
        return new TitleGenerator(client, "fake-model", PROMPT);
    }

    @Test
    @DisplayName("正常生成：清洗后返回短标题")
    void generatesTitle() {
        RecordingClient client = new RecordingClient("排查高峰期的连接超时");
        String title = generator(client).generate("服务高峰期总是超时", "先看日志……");
        print("生成结果 -> " + title);

        assertEquals("排查高峰期的连接超时", title);
    }

    @Test
    @DisplayName("真实提示词模板能渲染且两段输入都被注入")
    void promptIsRenderedWithBothInputs() {
        RecordingClient client = new RecordingClient("标题");
        generator(client).generate("我的提问内容", "助手的回答内容");

        String system = client.requests.get(0).messages().get(0).content();
        print("system prompt 长度 -> " + system.length());

        assertTrue(system.contains("我的提问内容"), "提问必须被注入");
        assertTrue(system.contains("助手的回答内容"), "回答必须被注入");
        assertTrue(system.contains("<用户的第一条提问>"), "数据边界标签必须在");
    }

    @Test
    @DisplayName("请求参数：复用主 agent 的模型、低温度、不设思考")
    void requestUsesStableParameters() {
        RecordingClient client = new RecordingClient("标题");
        generator(client).generate("提问", "回答");

        LlmRequest request = client.requests.get(0);
        print("model=" + request.model() + ", temperature=" + request.temperature()
                + ", maxTokens=" + request.maxTokens() + ", thinking=" + request.thinking());

        assertEquals("fake-model", request.model());
        assertEquals(0.2, request.temperature());
        assertEquals(64, request.maxTokens());
        assertNull(request.thinking(), "标题不需要推理");
        assertTrue(request.tools().isEmpty(), "标题生成不该挂工具");
        assertEquals(2, request.messages().size(), "system + user 两条");
    }

    @Test
    @DisplayName("去掉「标题：」前缀与包裹引号")
    void stripsDecoration() {
        print("clean(标题：排查超时) -> " + TitleGenerator.clean("标题：排查超时"));
        print("clean(\"排查超时\") -> " + TitleGenerator.clean("\"排查超时\""));
        print("clean(“排查超时”) -> " + TitleGenerator.clean("“排查超时”"));
        print("clean(「排查超时」) -> " + TitleGenerator.clean("「排查超时」"));
        print("clean(**排查超时**) -> " + TitleGenerator.clean("**排查超时**"));

        assertEquals("排查超时", TitleGenerator.clean("标题：排查超时"));
        assertEquals("排查超时", TitleGenerator.clean("标题: 排查超时"));
        assertEquals("排查超时", TitleGenerator.clean("\"排查超时\""));
        assertEquals("排查超时", TitleGenerator.clean("“排查超时”"));
        assertEquals("排查超时", TitleGenerator.clean("「排查超时」"));
        assertEquals("排查超时", TitleGenerator.clean("《排查超时》"));
        assertEquals("排查超时", TitleGenerator.clean("**排查超时**"));
    }

    @Test
    @DisplayName("去掉换行与控制字符（含 U+2028/U+2029）")
    void stripsControlCharacters() {
        // 行分隔符 U+2028 不是 Character.isWhitespace，光靠折叠空白压不掉
        String raw = "第一行 第二行 第三行";
        String cleaned = TitleGenerator.clean(raw);
        print("clean(带行分隔符) -> " + cleaned);

        assertEquals("第一行第二行第三行", cleaned);
    }

    @Test
    @DisplayName("超长输出被截断到 60 字")
    void truncatesLongOutput() {
        String long_ = "很长的标题".repeat(40);
        String cleaned = TitleGenerator.clean(long_);
        print("超长输出码点数 -> " + cleaned.codePointCount(0, cleaned.length()));

        assertEquals(61, cleaned.codePointCount(0, cleaned.length()));
    }

    @Test
    @DisplayName("清洗后为空返回 null，而不是空串")
    void emptyAfterCleanReturnsNull() {
        print("clean(\"\") -> " + TitleGenerator.clean(""));
        print("clean(\"  \\n \") -> " + TitleGenerator.clean("  \n "));
        print("clean(\"标题：\") -> " + TitleGenerator.clean("标题："));
        print("clean(null) -> " + TitleGenerator.clean(null));

        // 空串会覆盖掉占位标题、白白烧掉唯一一次改写机会
        assertNull(TitleGenerator.clean(""));
        assertNull(TitleGenerator.clean("  \n "));
        assertNull(TitleGenerator.clean("标题："));
        assertNull(TitleGenerator.clean(null));
    }

    @Test
    @DisplayName("超长输入被截到 1000 字再注入")
    void clipsOversizedInput() {
        RecordingClient client = new RecordingClient("标题");
        String huge = "啊".repeat(5000);
        generator(client).generate(huge, huge);

        String system = client.requests.get(0).messages().get(0).content();
        int occurrences = system.split("啊", -1).length - 1;
        print("注入的「啊」出现次数 -> " + occurrences);

        // 两段各截到 1000 码点（截断会带一个省略号）
        assertTrue(occurrences <= 2002, "输入必须被截断，实际 " + occurrences);
        assertTrue(occurrences >= 2000, "截断不该过头，实际 " + occurrences);
    }

    @Test
    @DisplayName("调用抛异常时返回 null，不外抛")
    void returnsNullOnFailure() {
        LlmClient failing = (request, listener) -> {
            throw new IllegalStateException("模型炸了");
        };
        String title = generator(failing).generate("提问", "回答");
        print("调用异常 -> " + title);

        assertNull(title);
    }

    @Test
    @DisplayName("模型返回 null 内容时返回 null")
    void returnsNullOnNullContent() {
        LlmClient nullContent = (request, listener) -> new LlmResponse("fake", null, List.of(), null);
        print("content=null -> " + generator(nullContent).generate("提问", "回答"));

        assertNull(generator(nullContent).generate("提问", "回答"));
    }

    @Test
    @DisplayName("模型为空时用兜底模型名，不外抛")
    void fallsBackWhenModelMissing() {
        RecordingClient client = new RecordingClient("标题");
        TitleGenerator generator = new TitleGenerator(client, null, PROMPT);

        assertEquals("标题", generator.generate("提问", "回答"));
        print("model=null 时实际请求的模型 -> " + client.requests.get(0).model());
    }
}
