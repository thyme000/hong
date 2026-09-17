package cn.thyme.memory.db;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话元信息的 MyBatis Mapper。除 {@code insertIfAbsent} 外均为 SQL-92，
 * 语句放在公共的 {@code SessionMapper.xml}；{@code insertIfAbsent} 的 upsert
 * 家族差异用 {@code databaseId} 标签在同一文件内区分。
 */
@Mapper
public interface SessionMapper {

    /**
     * 不存在则插入，已存在则原样不动。三个方言各一份实现。
     *
     * @return 受影响行数（0 表示已存在、未动）
     */
    int insertIfAbsent(@Param("id") String id,
                       @Param("title") String title,
                       @Param("titleSource") String titleSource,
                       @Param("createdAt") long createdAt,
                       @Param("updatedAt") long updatedAt);

    SessionRow findById(@Param("id") String id);

    /** 全部会话，按 updatedAt 倒序、同毫秒按 id 倒序。 */
    List<SessionRow> listByUpdatedAtDesc();

    int rename(@Param("id") String id,
               @Param("title") String title,
               @Param("titleSource") String titleSource,
               @Param("updatedAt") long updatedAt);

    /**
     * 仅当 {@code title_source} 仍等于 {@code autoSource} 时才更新。
     * 条件写进 WHERE 而不是先查再判，避免与用户改名在并发下互抢。
     *
     * @return 受影响行数（0 表示不存在或来源已不是 AUTO）
     */
    int updateTitleIfAuto(@Param("id") String id,
                          @Param("title") String title,
                          @Param("titleSource") String titleSource,
                          @Param("autoSource") String autoSource,
                          @Param("updatedAt") long updatedAt);

    int deleteById(@Param("id") String id);
}
