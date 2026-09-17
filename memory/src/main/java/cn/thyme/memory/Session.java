package cn.thyme.memory;

import java.time.Instant;

/**
 * 一个会话的元信息。标题<b>任何时候都不为空</b>——最差也是占位标题，
 * 因此消费方（如接口层拼响应体）不必再判空。
 *
 * @param id          会话 id
 * @param title       标题（不可为 null，空则归一为 {@link SessionTitle#DEFAULT_TITLE}）
 * @param titleSource 标题来源（null 归一为 {@link TitleSource#AUTO}）
 * @param createdAt   创建时刻
 * @param updatedAt   最后更新时刻（改名与标题改写都会推进）
 */
public record Session(String id,
                      String title,
                      TitleSource titleSource,
                      Instant createdAt,
                      Instant updatedAt) {

    /**
     * 归一化只有这一处：null 标题与 null 来源在这里被消掉，
     * 于是所有下游代码都可以假定二者非 null。
     */
    public Session {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        title = (title == null || title.isBlank()) ? SessionTitle.DEFAULT_TITLE : title;
        titleSource = titleSource == null ? TitleSource.AUTO : titleSource;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
