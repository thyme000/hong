package cn.thyme.memory.db;

/**
 * 数据库中的一行会话元信息。时间戳为 epoch 毫秒。
 *
 * <p>用全基本类型（{@code long} 而非 {@code Long}）：列都是 NOT NULL，
 * 没有 null 情形需要表达。</p>
 */
public class SessionRow {

    private String id;
    private String title;
    private String titleSource;
    private long createdAt;
    private long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitleSource() {
        return titleSource;
    }

    public void setTitleSource(String titleSource) {
        this.titleSource = titleSource;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
