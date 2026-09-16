package cn.thyme.llm.providers;

import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmException;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolCall;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式解析的行为测试：对着本地真实 HTTP 端点（JDK 自带的 {@link HttpServer}）验证
 * SSE 分帧、增量回调、工具调用分片累积与用量提取。
 *
 * <p>不碰真实模型、不依赖任何 mock 库——客户端本来就用 JDK {@code HttpClient}，
 * 造一个返回 {@code text/event-stream} 的本地端点是最贴近真实的桩。</p>
 */
class OpenAiCompatibleClientStreamTest {

    private HttpServer server;
    private String baseUrl;
    /** 服务端实际收到的请求体，用来断言 stream / stream_options 是否真的发出去了。 */
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    /** 本次要吐出的 SSE 内容，由各测试用例设置。 */
    private final AtomicReference<String> sse = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = sse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void willRespondWith(String sseContent) {
        sse.set(sseContent);
    }

    private LlmResponse chat(List<LlmDelta> deltas) {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(baseUrl, null);
        LlmRequest request = LlmRequest.builder().model("test-model").addUserMessage("你好").build();
        return client.chat(request, deltas::add);
    }

    private static String frame(String deltaJson) {
        return "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":" + deltaJson + "}]}\n\n";
    }

    @Test
    void 增量应按到达顺序回调且拼出完整文本() {
        willRespondWith(frame("{\"content\":\"你\"}")
                + frame("{\"content\":\"好\"}")
                + frame("{\"content\":\"世界\"}")
                + "data: [DONE]\n\n");

        List<LlmDelta> deltas = new ArrayList<>();
        LlmResponse response = chat(deltas);

        assertEquals(List.of("你", "好", "世界"), deltas.stream().map(LlmDelta::text).toList());
        assertTrue(deltas.stream().allMatch(d -> d.kind() == LlmDelta.Kind.CONTENT));
        assertEquals("你好世界", response.content());
    }

    @Test
    void 思考过程应与回答正文分开回调且不混入最终文本() {
        willRespondWith(frame("{\"reasoning_content\":\"让我想想\"}")
                + frame("{\"reasoning_content\":\"…想好了\"}")
                + frame("{\"content\":\"答案是 42\"}")
                + "data: [DONE]\n\n");

        List<LlmDelta> deltas = new ArrayList<>();
        LlmResponse response = chat(deltas);

        List<LlmDelta> reasoning = deltas.stream().filter(d -> d.kind() == LlmDelta.Kind.REASONING).toList();
        assertEquals(List.of("让我想想", "…想好了"), reasoning.stream().map(LlmDelta::text).toList());
        // 思考不落进 content，否则会被当回答存进历史
        assertEquals("答案是 42", response.content());
    }

    @Test
    void 空增量不回调() {
        willRespondWith(frame("{\"content\":\"\"}")
                + frame("{\"content\":\"有内容\"}")
                + "data: [DONE]\n\n");

        List<LlmDelta> deltas = new ArrayList<>();
        chat(deltas);

        assertEquals(1, deltas.size());
        assertEquals("有内容", deltas.get(0).text());
    }

    @Test
    void 工具调用参数分片应累积成一个完整调用() {
        willRespondWith("data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"id\":\"call_abc\",\"function\":{\"name\":\"read_file\"}}]}}]}\n\n"
                + "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n"
                + "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}\n\n"
                + "data: [DONE]\n\n");

        LlmResponse response = chat(new ArrayList<>());

        assertEquals(1, response.toolCalls().size());
        LlmToolCall call = response.toolCalls().get(0);
        assertEquals("call_abc", call.id());
        assertEquals("read_file", call.name());
        assertEquals("a.txt", call.arguments().get("path"));
    }

    @Test
    void 末块的usage应被提取出来() {
        willRespondWith(frame("{\"content\":\"好\"}")
                + "data: {\"model\":\"test-model\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8}}\n\n"
                + "data: [DONE]\n\n");

        LlmResponse response = chat(new ArrayList<>());

        assertEquals(128, response.usage().totalTokens());
    }

    @Test
    void 未带usage时用量为null而不是硬造一个() {
        willRespondWith(frame("{\"content\":\"好\"}") + "data: [DONE]\n\n");

        LlmResponse response = chat(new ArrayList<>());

        assertNull(response.usage());
    }

    @Test
    void 只有工具调用时content为null() {
        willRespondWith("data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"noop\",\"arguments\":\"{}\"}}]}}]}\n\n"
                + "data: [DONE]\n\n");

        LlmResponse response = chat(new ArrayList<>());

        assertNull(response.content());
        assertEquals(1, response.toolCalls().size());
    }

    @Test
    void 注释帧与畸形行不应中断解析() {
        willRespondWith(": ping\n\n"
                + "event: message\n"
                + frame("{\"content\":\"好\"}")
                + "data: 这不是 JSON\n\n"
                + "data: [DONE]\n\n");

        // 畸形 JSON 必须显式失败：静默跳过会让模型输出悄悄丢失，比报错更难查
        assertThrows(LlmException.class, () -> chat(new ArrayList<>()));
    }

    @Test
    void 心跳注释帧不影响正常解析() {
        willRespondWith(": ping\n\n" + frame("{\"content\":\"好\"}") + ": ping\n\n" + "data: [DONE]\n\n");

        LlmResponse response = chat(new ArrayList<>());

        assertEquals("好", response.content());
    }

    @Test
    void 调用方回调抛出的异常应原样传播以支持取消() {
        willRespondWith(frame("{\"content\":\"第\"}") + frame("{\"content\":\"二\"}") + "data: [DONE]\n\n");

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(baseUrl, null);
        LlmRequest request = LlmRequest.builder().model("test-model").addUserMessage("你好").build();

        // 取消就是靠这条通道：回调里抛，穿透解析循环，不被包装成 LlmException
        assertThrows(IllegalStateException.class, () -> client.chat(request, delta -> {
            throw new IllegalStateException("用户取消");
        }));
    }

    @Test
    void 请求应带上stream与include_usage() {
        willRespondWith(frame("{\"content\":\"好\"}") + "data: [DONE]\n\n");

        chat(new ArrayList<>());

        String body = receivedBody.get();
        assertTrue(body.contains("\"stream\":true"), "请求体应带 stream=true: " + body);
        assertTrue(body.contains("include_usage"), "请求体应带 stream_options.include_usage: " + body);
    }

    @Test
    void 服务端未返回任何数据帧时应显式报错() {
        willRespondWith(": ping\n\n");

        assertThrows(LlmException.class, () -> chat(new ArrayList<>()));
    }

    /**
     * 厂商包装类必须显式覆写新方法。漏掉覆写不会报错——它们会静默走
     * {@link cn.thyme.llm.LlmClient#IGNORE_DELTAS} 的默认方法，流式永不生效。
     * 这组用例是那条静默退化路径的唯一守卫。
     */
    @Test
    void Ollama包装类应把增量透传出去() {
        willRespondWith(frame("{\"content\":\"你\"}") + frame("{\"content\":\"好\"}") + "data: [DONE]\n\n");

        List<LlmDelta> deltas = new ArrayList<>();
        LlmResponse response = new OllamaLlmClient(baseUrl)
                .chat(LlmRequest.builder().model("m").addUserMessage("hi").build(), deltas::add);

        assertEquals(List.of("你", "好"), deltas.stream().map(LlmDelta::text).toList());
        assertEquals("你好", response.content());
    }

    @Test
    void SiliconFlow包装类应把增量透传出去() {
        willRespondWith(frame("{\"content\":\"你\"}") + frame("{\"content\":\"好\"}") + "data: [DONE]\n\n");

        List<LlmDelta> deltas = new ArrayList<>();
        LlmResponse response = new SiliconFlowLlmClient(baseUrl, "fake-key")
                .chat(LlmRequest.builder().model("m").addUserMessage("hi").build(), deltas::add);

        assertEquals(List.of("你", "好"), deltas.stream().map(LlmDelta::text).toList());
        assertEquals("你好", response.content());
    }
}
