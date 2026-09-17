package cn.thyme.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话元信息存储抽象：与 {@link MemoryStore} 平级，屏蔽底层存储方案。
 *
 * <p>会话是独立实体：消息历史存 {@link MemoryStore}，会话自身的标题与时间戳存这里。
 * 二者用同一套装配开关（{@code memory.store.type}）选实现，但职责不重叠。</p>
 */
public interface SessionStore {

    /**
     * 不存在则以给定标题与来源创建；<b>已存在则什么都不做</b>。
     *
     * <p>「不覆盖」是本方法存在的意义：调用发生在每轮对话开始时，若每次都写，
     * 用户改过的标题会被每轮打回占位标题。</p>
     *
     * @return 是否真的新建了（false = 已存在，原样未动）
     */
    boolean createIfAbsent(String id, String title, TitleSource source, Instant now);

    /** 按 id 读取会话元信息。 */
    Optional<Session> get(String id);

    /** 全部会话，按 {@code updatedAt} 倒序；同毫秒的按 id 倒序，保证顺序稳定。 */
    List<Session> list();

    /**
     * 用户改名：写入标题与 {@link TitleSource#USER}，并推进 {@code updatedAt}。
     *
     * @return 是否命中（false = 会话不存在）
     */
    boolean rename(String id, String title, Instant now);

    /**
     * 条件更新：<b>仅当标题来源仍是 {@link TitleSource#AUTO} 时</b>才写入。
     *
     * <p>「读-判-写」必须由存储层用一条带条件的语句完成，而不是调用方先
     * {@link #get} 再判断：那样在并发下会与用户改名抢——读到 AUTO，写入时用户的
     * USER 标题已被覆盖。这是与他人可能同时修改同一字段时唯一正确的写法。</p>
     *
     * @return 是否真的更新（false = 会话不存在，或已被用户/前一次改写改过）
     */
    boolean updateTitleIfAuto(String id, String title, TitleSource source, Instant now);

    /** 按 id 删除会话元信息，返回是否真的删除了。 */
    boolean delete(String id);
}
