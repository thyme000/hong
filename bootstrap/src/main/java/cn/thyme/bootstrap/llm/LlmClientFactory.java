package cn.thyme.bootstrap.llm;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 具体厂商适配器在此注册其 {@link LlmClient} 的注册表。调用方通过
 * {@link LlmVendor}（或配置 key）解析当前生效的客户端，而不是直接构造厂商
 * 客户端，从而在不改动调用方的前提下切换当前使用的模型。
 */
public final class LlmClientFactory {

    private final Map<LlmVendor, LlmClient> clients = new ConcurrentHashMap<>();

    public void register(LlmVendor vendor, LlmClient client) {
        if (vendor == null || client == null) {
            throw new IllegalArgumentException("vendor and client must not be null");
        }
        clients.put(vendor, client);
    }

    public Optional<LlmClient> get(LlmVendor vendor) {
        return Optional.ofNullable(clients.get(vendor));
    }

    public Optional<LlmClient> getByKey(String key) {
        for (LlmVendor vendor : LlmVendor.values()) {
            if (vendor.key().equals(key)) {
                return get(vendor);
            }
        }
        return Optional.empty();
    }
}
