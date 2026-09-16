package cn.thyme.llm.providers;

import cn.thyme.llm.LlmClient;
import cn.thyme.llm.model.LlmDelta;
import cn.thyme.llm.model.LlmException;
import cn.thyme.llm.model.LlmMessage;
import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmRole;
import cn.thyme.llm.model.LlmToolCall;
import cn.thyme.llm.model.LlmToolDefinition;
import cn.thyme.llm.model.LlmToolResult;
import cn.thyme.llm.model.LlmUsage;
import cn.thyme.llm.model.ThinkingLevel;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 通用的 OpenAI 兼容客户端：走 {@code POST {baseUrl}/chat/completions}，
 * baseUrl 应包含到 {@code /v1} 为止的前缀。支持消息、工具调用、token 用量、
 * 深度思考与可选的 API key（Authorization: Bearer）。
 *
 * <p>请求一律以流式发出（{@code stream: true}）：增量实时回调给调用方，完整响应
 * 在流读完后一次性返回。非流式响应没有增量可回调，若另开一条同步路径，两边的
 * 解析逻辑迟早会不一致——所以这里只有这一条路。</p>
 */
public class OpenAiCompatibleClient implements LlmClient {

    private final String chatCompletionsUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper json;

    public OpenAiCompatibleClient(String baseUrl, String apiKey) {
        this.chatCompletionsUrl = baseUrl + "/chat/completions";
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
    }

    @Override
    public LlmResponse chat(LlmRequest request, Consumer<LlmDelta> deltaListener) {
        try {
            String body = json.writeValueAsString(toRequestBody(request));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest httpRequest = builder
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            // 必须自己逐行消费 InputStream：BodyHandlers 是一次性的，一旦用 ofString()
            // 把响应整体读成字符串，就再也拿不到增量，流式无从谈起
            HttpResponse<InputStream> httpResponse =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = httpResponse.body();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return readStream(reader, deltaListener);
            }
        } catch (IOException e) {
            throw new LlmException("调用模型失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用模型被中断", e);
        }
    }

    /**
     * 消费 SSE 流：逐行解析 {@code data:} 帧，把增量回调出去，最后拼出完整响应。
     *
     * <p>只有本方法声明的两类受检异常会被转成 {@link LlmException}；调用方（例如
     * {@code deltaListener}）抛出的运行时异常原样向上传播，这是「中途取消」依赖的通道。</p>
     */
    private LlmResponse readStream(BufferedReader reader, Consumer<LlmDelta> deltaListener)
            throws IOException {
        String model = null;
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // tool_calls 在流里按 index 分片到达：id 与 name 只在首片给，arguments 每片追加
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        LlmUsage usage = null;
        boolean sawData = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith(":")) {
                // 空行是帧分隔符；冒号开头是注释（保活心跳），按 SSE 规范忽略
                continue;
            }
            if (!line.startsWith("data:")) {
                // event: / id: / retry: 等字段本客户端不使用
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }
            sawData = true;

            JsonNode root;
            try {
                root = json.readTree(payload);
            } catch (JacksonException e) {
                throw new LlmException("模型流式响应不是合法 JSON: " + payload, e);
            }
            if (root.path("model").isTextual()) {
                model = root.get("model").asText();
            }
            JsonNode usageNode = root.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                usage = toUsage(usageNode);
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                // 开着 include_usage 时，末块专门用来带 usage，choices 为空
                continue;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null || delta.isNull()) {
                continue;
            }
            // 思考增量的字段名各厂商不统一：DeepSeek / vLLM / 硅基流动用
            // reasoning_content，而 Ollama 的 OpenAI 兼容层用 reasoning。
            // 只认其中一个会让另一家的思考被静默丢掉——不报错、帧就是不出现
            emit(reasoning, firstText(delta, "reasoning_content", "reasoning"),
                    LlmDelta.Kind.REASONING, deltaListener);
            emit(content, textOrNull(delta.get("content")), LlmDelta.Kind.CONTENT, deltaListener);
            accumulateToolCalls(toolCalls, delta.get("tool_calls"));
        }

        if (!sawData) {
            throw new LlmException("模型未返回任何流式数据: " + chatCompletionsUrl);
        }
        // content 为空串（只有工具调用或只有思考）时保持 null，与 AgentResult 的契约一致
        String finalContent = content.isEmpty() ? null : content.toString();
        List<LlmToolCall> calls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            calls.add(acc.toToolCall(json));
        }
        return new LlmResponse(model == null ? "" : model, finalContent, calls, usage);
    }

    /** 非空增量才回调，空片段（厂商的心跳块、只有 tool_calls 的块）直接丢弃。 */
    private static void emit(StringBuilder sink, String text,
                             LlmDelta.Kind kind, Consumer<LlmDelta> listener) {
        if (text == null || text.isEmpty()) {
            return;
        }
        sink.append(text);
        listener.accept(new LlmDelta(kind, text));
    }

    private static void accumulateToolCalls(Map<Integer, ToolCallAccumulator> accumulators, JsonNode deltaToolCalls) {
        if (deltaToolCalls == null || !deltaToolCalls.isArray()) {
            return;
        }
        for (JsonNode call : deltaToolCalls) {
            // 少数厂商不给 index（单工具调用时），此时所有分片都落在同一个槽位
            int index = call.path("index").isInt() ? call.get("index").asInt() : 0;
            accumulators.computeIfAbsent(index, k -> new ToolCallAccumulator())
                    .merge(call, accumulators.size());
        }
    }

    /** 一条工具调用在流里的分片累积：id / 名称只到一次，参数逐片拼接。 */
    private static final class ToolCallAccumulator {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(JsonNode deltaCall, int fallbackId) {
            if (deltaCall.path("id").isTextual()) {
                id = deltaCall.get("id").asText();
            }
            JsonNode function = deltaCall.get("function");
            if (function == null || function.isNull()) {
                return;
            }
            if (function.path("name").isTextual()) {
                name = function.get("name").asText();
            }
            if (function.path("arguments").isTextual()) {
                arguments.append(function.get("arguments").asText());
            }
        }

        LlmToolCall toToolCall(ObjectMapper json) {
            // 没收到 id 时兜一个稳定值：后续回填工具结果要用它配对
            String callId = id == null ? "call_" + fallbackId() : id;
            return new LlmToolCall(callId, name, parseArguments(json, arguments.toString()));
        }

        private String fallbackId() {
            return Integer.toHexString(System.identityHashCode(this));
        }
    }

    private static Map<String, Object> parseArguments(ObjectMapper json, String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, Map.class);
        } catch (JacksonException e) {
            // 参数拼不完整（模型输出被截断、厂商分片异常）时不能让整轮对话失败：
            // 退化成空参数，工具自己会因必填缺失回报失败
            return Map.of();
        }
    }

    private Map<String, Object> toRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", toMessages(request.messages()));
        body.put("stream", true);
        // 不显式要求，多数厂商的流式响应根本不带 usage，token 预算就只剩字符估算可用
        body.put("stream_options", Map.of("include_usage", true));
        if (!request.tools().isEmpty()) {
            body.put("tools", toTools(request.tools()));
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.thinking() != null) {
            body.put("reasoning_effort", toReasoningEffort(request.thinking()));
        }
        return body;
    }

    private static String toReasoningEffort(ThinkingLevel level) {
        return switch (level) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
        };
    }

    private List<Map<String, Object>> toMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (!message.toolCalls().isEmpty()) {
                result.add(toAssistantToolCall(message));
            } else if (!message.toolResults().isEmpty()) {
                result.addAll(toToolResults(message.toolResults()));
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", toOpenAiRole(message.role()));
                m.put("content", message.content());
                result.add(m);
            }
        }
        return result;
    }

    private Map<String, Object> toAssistantToolCall(LlmMessage message) {
        List<Map<String, Object>> toolCalls = message.toolCalls().stream()
                .map(this::toToolCall)
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", message.content());
        m.put("tool_calls", toolCalls);
        return m;
    }

    private Map<String, Object> toToolCall(LlmToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", json.writeValueAsString(call.arguments()));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", call.id());
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }

    private List<Map<String, Object>> toToolResults(List<LlmToolResult> toolResults) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmToolResult toolResult : toolResults) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "tool");
            m.put("tool_call_id", toolResult.toolCallId());
            m.put("content", toolResult.content());
            result.add(m);
        }
        return result;
    }

    private List<Map<String, Object>> toTools(List<LlmToolDefinition> tools) {
        return tools.stream()
                .map(tool -> {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", tool.name());
                    function.put("description", tool.description());
                    function.put("parameters", tool.inputSchema());
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("type", "function");
                    t.put("function", function);
                    return t;
                })
                .toList();
    }

    private LlmUsage toUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Integer input = node.has("prompt_tokens") ? node.get("prompt_tokens").asInt() : null;
        Integer output = node.has("completion_tokens") ? node.get("completion_tokens").asInt() : null;
        return new LlmUsage(input, output);
    }

    private static String toOpenAiRole(LlmRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 按顺序取第一个非空文本字段；用于同一语义有多种字段名的情形。 */
    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String text = textOrNull(node.get(name));
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }
}
