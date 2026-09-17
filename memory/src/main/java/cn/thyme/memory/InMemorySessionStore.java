package cn.thyme.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话元信息的内存实现：线程安全，适合测试与默认场景。
 *
 * <p>排序与数据库实现保持一致：{@code updatedAt} 倒序，同毫秒按 id 倒序。
 * 两个实现在顺序上不一致会让「内存模式看起来对、切到 SQLite 就变样」这种问题
 * 只在部署后才出现。</p>
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public boolean createIfAbsent(String id, String title, TitleSource source, Instant now) {
        Session created = new Session(id, title, source, now, now);
        // 必须是 putIfAbsent 而非 put：put 会让「已存在则不覆盖」在内存分支失效，
        // 而数据库分支（ON CONFLICT DO NOTHING）是对的——那正是本方法唯一要紧的语义
        return sessions.putIfAbsent(created.id(), created) == null;
    }

    @Override
    public Optional<Session> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<Session> list() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(Session::updatedAt)
                        .thenComparing(Session::id)
                        .reversed())
                .toList();
    }

    @Override
    public boolean rename(String id, String title, Instant now) {
        return sessions.computeIfPresent(id, (key, current) ->
                new Session(key, title, TitleSource.USER, current.createdAt(), now)) != null;
    }

    @Override
    public boolean updateTitleIfAuto(String id, String title, TitleSource source, Instant now) {
        // computeIfPresent 的映射函数在键的锁内执行，因此这里的「读-判-写」与数据库
        // 侧的带条件 UPDATE 等价：判断与写入之间没有别的写者能插进来。
        // 返回值必须区分「真的改了」与「来源已不是 AUTO 所以没动」——后者是并发下
        // 的正常结果（用户改名或另一次改写先到），调用方与测试都靠它分辨
        boolean[] updated = new boolean[1];
        sessions.computeIfPresent(id, (key, current) -> {
            if (current.titleSource() != TitleSource.AUTO) {
                return current;
            }
            updated[0] = true;
            return new Session(key, title, source, current.createdAt(), now);
        });
        return updated[0];
    }

    @Override
    public boolean delete(String id) {
        return sessions.remove(id) != null;
    }
}
