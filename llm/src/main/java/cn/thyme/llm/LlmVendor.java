package cn.thyme.llm;

public enum LlmVendor {
    /** 本地模型运行时 Ollama，其适配器在该 key 下注册。 */
    OLLAMA("ollama"),
    /** 硅基流动（SiliconFlow）云模型服务，其适配器在该 key 下注册。 */
    SILICONFLOW("siliconflow");

    private final String key;

    LlmVendor(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
