package cn.thyme.llm;

import cn.thyme.llm.model.LlmRequest;
import cn.thyme.llm.model.LlmResponse;
import cn.thyme.llm.model.LlmToolDefinition;
import cn.thyme.llm.providers.OllamaLlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Ollama 调用测试（非流式）。需本地 Ollama 服务已运行、模型已 pull。
 * URL 与模型名从根目录 application.yml 读取，不硬编码。
 */
class OllamaLlmClientTest {

    @Test
    @DisplayName("调用 Ollama 返回文本")
    void callsOllama() {
        OllamaLlmClient client = new OllamaLlmClient(ProviderConfig.url("ollama"));

        LlmRequest request = LlmRequest.builder()
                .model(ProviderConfig.model("ollama"))
                .addUserMessage("用一句话介绍你自己")
                .build();

        LlmResponse response = client.chat(request);

        System.out.println(">> 响应内容: " + response.content());
        System.out.println(">> token 用量: " + response.usage());

        assertNotNull(response.content());
        assertFalse(response.content().isBlank());
    }

    @Test
    @DisplayName("调用 Ollama 返回工具调用")
    void callsOllamaWithTools() {
        OllamaLlmClient client = new OllamaLlmClient(ProviderConfig.url("ollama"));

        LlmRequest request = LlmRequest.builder()
                .model(ProviderConfig.model("ollama"))
                .addUserMessage("北京今天天气怎么样？")
                .addTool(new LlmToolDefinition(
                        "get_weather",
                        "查询指定城市的天气",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city"))))
                .build();

        LlmResponse response = client.chat(request);

        System.out.println(">> 文本内容: " + response.content());
        System.out.println(">> 工具调用: " + response.toolCalls());
        System.out.println(">> token 用量: " + response.usage());

        // 模型要么返回文本，要么返回工具调用，二者至少有一个
        assertFalse(response.content() == null && response.toolCalls().isEmpty());
    }
}
