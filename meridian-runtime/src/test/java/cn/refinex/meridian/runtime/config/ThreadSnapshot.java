package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 一个会话主线程在数据库中的可变部分的快照：智能体状态、事件、轮次、工具账本、收件箱与待处理动作。比较前后两次快照，
 * 证明失去租约的旧执行什么都没写进去。
 *
 * @param states 状态行（含版本、内容 epoch 与所属轮次、更新时间）
 * @param session 会话行的状态与 {@code last_seq}
 * @param events 事件数
 * @param turns 轮次行
 * @param invocations 工具账本行
 * @param inbox 收件箱行
 * @param actions 待处理动作数
 */
record ThreadSnapshot(
        List<Map<String, Object>> states,
        List<Map<String, Object>> session,
        List<Long> events,
        List<Map<String, Object>> turns,
        List<Map<String, Object>> invocations,
        List<Map<String, Object>> inbox,
        List<Long> actions) {

    private static final JdbcClient JDBC = JdbcClient.create(TestDatabase.migratorDataSource());

    static ThreadSnapshot take(TestSession session) {
        return new ThreadSnapshot(
                rows(
                        "SELECT state_key, state::text AS state, version, content_epoch, content_turn_id, updated_at"
                                + " FROM rt.agent_states WHERE thread_id = ? ORDER BY state_key",
                        session.mainThreadId()),
                rows("SELECT status, last_seq, updated_at FROM rt.sessions WHERE id = ?", session.sessionId()),
                JDBC.sql("SELECT count(*) FROM rt.session_events WHERE session_id = ?")
                        .param(session.sessionId())
                        .query(Long.class)
                        .list(),
                rows(
                        "SELECT id, status, lease_owner, lease_epoch, lease_expires_at, recovery_attempt, stop_reason,"
                                + " updated_at FROM rt.turns WHERE thread_id = ? ORDER BY id",
                        session.mainThreadId()),
                rows(
                        "SELECT id, status, result_ref::text AS result_ref, finished_at FROM rt.tool_invocations"
                                + " WHERE thread_id = ? ORDER BY id",
                        session.mainThreadId()),
                rows(
                        "SELECT id, status, consumed_turn_id, consumed_epoch FROM rt.inbox_messages"
                                + " WHERE thread_id = ? ORDER BY id",
                        session.mainThreadId()),
                JDBC.sql("SELECT count(*) FROM rt.pending_actions WHERE thread_id = ?")
                        .param(session.mainThreadId())
                        .query(Long.class)
                        .list());
    }

    private static List<Map<String, Object>> rows(String sql, String param) {
        return JDBC.sql(sql).param(param).query().listOfRows();
    }
}
