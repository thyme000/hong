package cn.thyme.bootstrap.llm;

public enum LlmVendor {
    /** 外部模型服务 joyAgent，其适配器在该 key 下注册。 */
    JOY_AGENT("joyAgent");

    private final String key;

    LlmVendor(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
