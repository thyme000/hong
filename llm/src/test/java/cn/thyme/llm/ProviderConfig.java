package cn.thyme.llm;

/**
 * 测试辅助：提供 LLM provider 的连接配置。
 * URL 与模型名硬编码（非敏感），API key 从环境变量读取。
 */
final class ProviderConfig {

    private ProviderConfig() {
    }

    static String url(String name) {
        return switch (name) {
            case "ollama" -> "http://localhost:11434/v1";
            case "siliconflow" -> "https://api.siliconflow.cn/v1";
            default -> throw new IllegalArgumentException("未知提供商: " + name);
        };
    }

    static String apiKey(String name) {
        return "siliconflow".equals(name) ? System.getenv("SILICONFLOW_API_KEY") : null;
    }

    static String model(String name) {
        return switch (name) {
            case "ollama" -> "qwen3.5:9b";
            case "siliconflow" -> "deepseek-ai/DeepSeek-V4-Flash";
            default -> throw new IllegalArgumentException("未知提供商: " + name);
        };
    }
}
