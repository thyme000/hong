package cn.thyme.memory;

/**
 * 会话标题的来源，决定标题能否被自动改写覆盖。
 *
 * <ul>
 *   <li>{@link #AUTO} —— 由首条提问截断而来的占位标题，是唯一允许被自动改写覆盖的状态</li>
 *   <li>{@link #GENERATED} —— 已由模型改写生成</li>
 *   <li>{@link #USER} —— 用户手工改名，任何自动改写都不得覆盖</li>
 * </ul>
 */
public enum TitleSource {

    AUTO,
    GENERATED,
    USER;

    /** 存储用的线格式取值（小写）。 */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 解析存储值。**未知取值一律回落 {@link #AUTO} 而不抛异常**：库中可能存在旧版本
     * 写入的值（或将来新增的枚举值被回滚后的残留），在读取路径上抛异常会让一条脏行
     * 把整个会话列表变成 500。回落是安全的——AUTO 意味着「可被重新生成」，
     * 这是三个取值里最保守的选择。
     */
    public static TitleSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        for (TitleSource source : values()) {
            if (source.wire().equalsIgnoreCase(raw.trim())) {
                return source;
            }
        }
        return AUTO;
    }
}
