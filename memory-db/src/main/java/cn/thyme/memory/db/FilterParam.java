package cn.thyme.memory.db;

/**
 * 数据库查询用的筛选参数：key 是 metadata 键名，value 已转为字符串。
 */
public class FilterParam {

    private final String key;
    private final String value;

    public FilterParam(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
