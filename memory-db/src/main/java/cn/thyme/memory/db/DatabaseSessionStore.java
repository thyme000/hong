package cn.thyme.memory.db;

import cn.thyme.memory.Session;
import cn.thyme.memory.SessionStore;
import cn.thyme.memory.TitleSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话元信息的数据库实现：基于 MyBatis，时间戳以 epoch 毫秒存储。
 *
 * <p>Instant 与线格式的转换只发生在本类边界上，存储层与调用方都不必关心
 * {@code title_source} 的字面量写法。</p>
 */
public class DatabaseSessionStore implements SessionStore {

    private final SessionMapper mapper;

    public DatabaseSessionStore(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean createIfAbsent(String id, String title, TitleSource source, Instant now) {
        long millis = millis(now);
        String wire = source.wire();
        try {
            return mapper.insertIfAbsent(id, title, wire, millis, millis) > 0;
        } catch (DataIntegrityViolationException e) {
            // H2 的 NOT EXISTS 有残留竞态：两个线程都通过检查，一个撞主键。
            // 「已存在」正是本方法的正常结果之一，不该让调用方看到异常。
            // 捕获 Spring 的翻译后异常而非原始 JDBC 异常——MyBatis-Spring 会把
            // SQLException 转成 DataAccessException，原始类型到不了这一层
            return false;
        }
    }

    @Override
    public Optional<Session> get(String id) {
        return Optional.ofNullable(mapper.findById(id)).map(this::toSession);
    }

    @Override
    public List<Session> list() {
        return mapper.listByUpdatedAtDesc().stream().map(this::toSession).toList();
    }

    @Override
    public boolean rename(String id, String title, Instant now) {
        return mapper.rename(id, title, TitleSource.USER.wire(), millis(now)) > 0;
    }

    @Override
    public boolean updateTitleIfAuto(String id, String title, TitleSource source, Instant now) {
        return mapper.updateTitleIfAuto(id, title, source.wire(), TitleSource.AUTO.wire(), millis(now)) > 0;
    }

    @Override
    public boolean delete(String id) {
        return mapper.deleteById(id) > 0;
    }

    private Session toSession(SessionRow row) {
        return new Session(row.getId(), row.getTitle(), TitleSource.parse(row.getTitleSource()),
                Instant.ofEpochMilli(row.getCreatedAt()), Instant.ofEpochMilli(row.getUpdatedAt()));
    }

    private static long millis(Instant instant) {
        return (instant == null ? Instant.now() : instant).toEpochMilli();
    }
}
