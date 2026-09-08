package cn.thyme.llm;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 测试辅助：从项目根目录的 application.yml 读取 LLM provider 配置，
 * 避免在测试代码中硬编码 URL、API key 等敏感信息。
 */
final class ProviderConfig {

    private static final Map<String, Object> ROOT = load();

    private ProviderConfig() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        Path path = Path.of("../application.yml");
        if (!Files.exists(path)) {
            path = Path.of("application.yml");
        }
        try {
            String text = Files.readString(path);
            return new Yaml().load(text);
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> provider(String name) {
        Map<String, Object> llm = (Map<String, Object>) ROOT.get("LLM");
        Map<String, Object> providers = (Map<String, Object>) llm.get("providers");
        return (Map<String, Object>) providers.get(name);
    }

    static String url(String name) {
        return (String) provider(name).get("url");
    }

    static String apiKey(String name) {
        Object key = provider(name).get("apikey");
        return key == null ? null : String.valueOf(key);
    }

    static String model(String name) {
        Object models = provider(name).get("models");
        if (models instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return null;
    }
}
