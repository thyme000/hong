package cn.thyme.llm.providers;

import cn.thyme.llm.LlmClient;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用的 OpenAI 兼容客户端：走 {@code POST {baseUrl}/chat/completions}，
 * baseUrl 应包含到 {@code /v1} 为止的前缀。支持消息、工具调用、token 用量、
 * 深度思考与可选的 API key（Authorization: Bearer）。
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
    public LlmResponse chat(LlmRequest request) {
        try {
            String body = json.writeValueAsString(toRequestBody(request));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest httpRequest = builder
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return toResponse(json.readTree(httpResponse.body()));
        } catch (IOException e) {
            throw new LlmException("调用模型失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用模型被中断", e);
        }
    }

    private Map<String, Object> toRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", toMessages(request.messages()));
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

    private LlmResponse toResponse(JsonNode root) {
        JsonNode choice = root.get("choices").get(0);
        JsonNode message = choice.get("message");
        String content = textOrNull(message.get("content"));
        List<LlmToolCall> toolCalls = toToolCalls(message.get("tool_calls"));
        LlmUsage usage = toUsage(root.get("usage"));
        return new LlmResponse(root.path("model").asText(), content, toolCalls, usage);
    }

    private List<LlmToolCall> toToolCalls(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<LlmToolCall> result = new ArrayList<>();
        for (JsonNode call : node) {
            JsonNode function = call.get("function");
            Map<String, Object> arguments = parseArguments(function.get("arguments"));
            result.add(new LlmToolCall(call.get("id").asText(), function.get("name").asText(), arguments));
        }
        return result;
    }

    private Map<String, Object> parseArguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return json.readValue(node.asText(), Map.class);
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
}
