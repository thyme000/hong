package cn.thyme.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储抽象：屏蔽底层存储方案（内存、文件、数据库等）。
 * 实现类负责具体的存取与查询，调用方只依赖本接口。
 */
public interface MemoryStore {

    /** 保存一条记录，按 id 覆盖（upsert）。 */
    void save(MemoryRecord record);

    /** 按 id 读取记录。 */
    Optional<MemoryRecord> get(String id);

    /** 按 id 删除记录，返回是否真的删除了。 */
    boolean delete(String id);

    /** 按条件查询记录，支持分页。 */
    List<MemoryRecord> query(MemoryQuery query);

    /** 返回全部记录。 */
    List<MemoryRecord> list();
}
