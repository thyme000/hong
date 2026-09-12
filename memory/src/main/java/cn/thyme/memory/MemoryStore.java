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

    /**
     * 按条件删除记录，返回删除条数。筛选语义与 {@link #query} 一致，
     * 忽略分页。用于按 sessionId 清理一个会话的全部消息等场景。
     *
     * <p>筛选条件<b>不得为空</b>：空条件等价于清空全部记录，
     * 本方法会直接拒绝，避免误删整表。</p>
     */
    int deleteWhere(MemoryQuery query);

    /** 校验批量删除的筛选条件非空，供各实现复用。 */
    static void requireFilters(MemoryQuery query) {
        if (query == null || query.filters().isEmpty()) {
            throw new IllegalArgumentException("批量删除必须指定筛选条件，禁止无条件下清空");
        }
    }

    /** 按条件查询记录，支持分页。 */
    List<MemoryRecord> query(MemoryQuery query);

    /** 返回全部记录。 */
    List<MemoryRecord> list();
}
