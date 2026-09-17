CREATE TABLE IF NOT EXISTS memory_record (
    id VARCHAR(255) PRIMARY KEY,
    content TEXT NOT NULL,
    metadata TEXT
);

-- 会话元信息。会话是独立实体：消息历史在 memory_record，
-- 标题与时间戳在这里，故列表接口不再需要全表扫消息去反推会话。
CREATE TABLE IF NOT EXISTS session_record (
    id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    -- auto（占位，可被改写覆盖）/ generated（模型生成）/ user（用户手改）
    title_source VARCHAR(16) NOT NULL,
    -- epoch 毫秒。不用 TIMESTAMP：SQLite 无日期类型、H2 是 TIMESTAMP(6)、
    -- PG 无时区，三家语义不一；也不用 ISO 字符串，其小数位宽度可变，
    -- 「恰好落在整秒」的写会排到带毫秒的写后面，排序键上静默乱序
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 列表按 updated_at 倒序取全量，索引列顺序与之对齐；id 是次序兜底，
-- 否则同毫秒写入的会话在两次请求间可能互换位置，列表看起来会自己重排
CREATE INDEX IF NOT EXISTS idx_session_record_updated_at
    ON session_record (updated_at DESC, id DESC);
