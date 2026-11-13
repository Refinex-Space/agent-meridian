package cn.refinex.meridian.runtime.session.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.session.domain.SessionStats.TurnError;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/**
 * 会话统计的项目级查询（{@code GET /v1/session-stats}，ADR-0053）。计数走 V0012 的部分索引，失败率读后台汇总的
 * {@code rt.agent_turn_stats}，都不在主路径上做额外写入。
 */
@Repository
public class SessionStatsRepository extends TenantScopedRepository {

    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {};

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionStatsRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 执行中（含被置回队列）与等待回应的会话数，走 {@code sessions_live_status_idx}。
     *
     * @param agentId 只统计该智能体的会话；可为空
     * @return {@code [执行中, 等待回应]}
     */
    public long[] liveSessions(String agentId) {
        return sql("""
                        SELECT count(*) FILTER (WHERE status IN ('running', 'rescheduled')) AS running,
                               count(*) FILTER (WHERE status = 'requires_action') AS requires_action
                          FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND status IN ('running', 'requires_action', 'rescheduled')
                           AND (CAST(:agent_filter AS text) IS NULL OR agent_id = :agent_filter)
                        """)
                .param("agent_filter", agentId)
                .query((rs, row) -> new long[] {rs.getLong("running"), rs.getLong("requires_action")})
                .single();
    }

    /**
     * 等待回应的待处理动作数，走 {@code pending_actions_project_pending_idx}。
     *
     * @param agentId 只统计该智能体的会话中的动作；可为空
     * @return 动作数
     */
    public long pendingActions(String agentId) {
        return sql("""
                        SELECT count(*)
                          FROM rt.pending_actions a
                         WHERE a.tenant_id = :tenant_id AND a.project_id = :project_id AND a.status = 'pending'
                           AND (CAST(:agent_filter AS text) IS NULL
                                OR EXISTS (SELECT 1 FROM rt.sessions s
                                            WHERE s.id = a.session_id AND s.agent_id = :agent_filter))
                        """).param("agent_filter", agentId).query(Long.class).single();
    }

    /**
     * 每个智能体使用中（未终止、未删除）与执行中的会话数，按智能体 ID 排序。
     *
     * @param agentId 只统计该智能体；可为空
     * @return 智能体 ID → {@code [使用中, 执行中]}
     */
    public Map<String, long[]> activeSessionsByAgent(String agentId) {
        Map<String, long[]> counts = new TreeMap<>();
        sql("""
                        SELECT agent_id, count(*) AS active,
                               count(*) FILTER (WHERE status IN ('running', 'rescheduled')) AS running
                          FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND deleted_at IS NULL
                           AND agent_id IS NOT NULL AND status <> 'terminated'
                           AND (CAST(:agent_filter AS text) IS NULL OR agent_id = :agent_filter)
                         GROUP BY agent_id
                        """)
                .param("agent_filter", agentId)
                .query((rs, row) ->
                        counts.put(rs.getString("agent_id"), new long[] {rs.getLong("active"), rs.getLong("running")}))
                .list();
        return counts;
    }

    /**
     * 时间窗内每个智能体结束的轮次与失败的轮次（小时汇总表）。
     *
     * @param windowStart 时间窗的起点（整点）
     * @param agentId 只统计该智能体；可为空
     * @return 智能体 ID → {@code [轮次, 失败]}
     */
    public Map<String, long[]> turnsByAgent(Instant windowStart, String agentId) {
        Map<String, long[]> counts = new TreeMap<>();
        sql("""
                        SELECT agent_id, sum(turns) AS turns, sum(failed_turns) AS failed
                          FROM rt.agent_turn_stats
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND hour_start >= :window_start
                           AND (CAST(:agent_filter AS text) IS NULL OR agent_id = :agent_filter)
                         GROUP BY agent_id
                        """)
                .param("window_start", windowStart)
                .param("agent_filter", agentId)
                .query((rs, row) ->
                        counts.put(rs.getString("agent_id"), new long[] {rs.getLong("turns"), rs.getLong("failed")}))
                .list();
        return counts;
    }

    /**
     * 时间窗内最近的失败轮次（{@code turns_failed_idx}），不含已删除会话的。错误码与信息取该轮最后一个 {@code session.error} 事件。
     *
     * @param windowStart 时间窗的起点
     * @param agentId 只看该智能体；可为空
     * @param limit 至多几个
     * @return 失败的轮次，新的在前
     */
    public List<TurnError> recentErrors(Instant windowStart, String agentId, int limit) {
        List<TurnError> failed = sql("""
                        SELECT t.id, t.session_id, t.thread_id, t.finished_at, s.agent_id
                          FROM rt.turns t
                          JOIN rt.sessions s ON s.id = t.session_id
                         WHERE t.tenant_id = :tenant_id AND t.project_id = :project_id AND t.status = 'failed'
                           AND t.finished_at >= :window_start AND s.deleted_at IS NULL
                           AND (CAST(:agent_filter AS text) IS NULL OR s.agent_id = :agent_filter)
                         ORDER BY t.finished_at DESC, t.id DESC
                         LIMIT :limit
                        """)
                .param("window_start", windowStart)
                .param("agent_filter", agentId)
                .param("limit", limit)
                .query((rs, row) -> new TurnError(
                        rs.getString("session_id"),
                        rs.getString("thread_id"),
                        rs.getString("id"),
                        rs.getString("agent_id"),
                        null,
                        null,
                        JdbcValues.instant(rs, "finished_at")))
                .list();
        return failed.stream().map(this::withError).toList();
    }

    private TurnError withError(TurnError turn) {
        Optional<String> data = sql("""
                        SELECT data::text
                          FROM rt.session_events
                         WHERE session_id = :session_id AND tenant_id = :tenant_id AND project_id = :project_id
                           AND turn_id = :turn_id AND type = 'session.error'
                         ORDER BY seq DESC
                         LIMIT 1
                        """)
                .param("session_id", turn.sessionId())
                .param("turn_id", turn.turnId())
                .query(String.class)
                .optional();
        if (data.isEmpty()) {
            return turn;
        }
        Map<String, Object> error = JsonMappers.lenient().readValue(data.get(), DATA);
        return new TurnError(
                turn.sessionId(),
                turn.threadId(),
                turn.turnId(),
                turn.agentId(),
                error.get("code") instanceof String code ? code : null,
                error.get("message") instanceof String message ? message : null,
                turn.occurredAt());
    }
}
