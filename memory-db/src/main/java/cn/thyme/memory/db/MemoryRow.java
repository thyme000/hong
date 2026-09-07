package cn.thyme.memory.db;

/**
 * 数据库中的一条记录行。metadata 以 JSON 字符串存储。
 */
public class MemoryRow {

    private String id;
    private String content;
    private String metadata;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
