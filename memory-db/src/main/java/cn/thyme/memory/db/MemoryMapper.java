package cn.thyme.memory.db;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆记录的 MyBatis Mapper。query 与 upsert 因数据库方言不同，
 * 在 XML 中通过 databaseIdProvider 区分。
 */
@Mapper
public interface MemoryMapper {

    void upsert(@Param("id") String id, @Param("content") String content, @Param("metadata") String metadata);

    MemoryRow findById(@Param("id") String id);

    int deleteById(@Param("id") String id);

    List<MemoryRow> query(@Param("filters") List<FilterParam> filters,
                          @Param("offset") int offset,
                          @Param("limit") int limit);

    List<MemoryRow> listAll();
}
