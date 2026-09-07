package cn.thyme.memory.filter;

import cn.thyme.memory.MemoryRecord;

/**
 * 记忆查询的筛选条件。当前只有精确匹配（{@link EqFilter}），
 * 后续可扩展包含、范围、排序等新类型，而不改动 {@code MemoryQuery} 接口。
 */
public interface Filter {

    /** 判断一条记录是否满足本条件（供内存实现使用）。 */
    boolean matches(MemoryRecord record);
}
