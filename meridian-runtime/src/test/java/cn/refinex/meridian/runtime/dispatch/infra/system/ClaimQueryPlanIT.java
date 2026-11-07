package cn.refinex.meridian.runtime.dispatch.infra.system;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 认领、回收、租约条件三条热路径 SQL 的执行计划走索引（T05「实现要点」）。造数：2000 个会话，每个会话 10 个已结束的轮次，
 * 其中 50 个会话各有 1 个待认领轮次、20 个会话各有 1 个执行中的轮次，5 个租户；{@code ANALYZE} 后以应用角色 {@code EXPLAIN}。
 * 计划输出到测试日志，结论写在模块 {@code CLAUDE.md}。
 */
class ClaimQueryPlanIT {

    private static final Logger log = LoggerFactory.getLogger(ClaimQueryPlanIT.class);

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

    private static JdbcClient app;

    @BeforeAll
    static void seed() {
        RuntimeTestDatabase.truncate();
        app = JdbcClient.create(RuntimeTestDatabase.dataSource());
        DbAssertions.update("""
                INSERT INTO rt.tenant_concurrency (tenant_id, running, max_running, updated_at)
                SELECT 'ten_' || t, 0, 20, now() FROM generate_series(1, 5) t
                """);
        DbAssertions.update("""
                INSERT INTO rt.sessions (id, tenant_id, project_id, agent_version_id, environment_id,
                                         environment_revision, status, last_activity_at, created_at, updated_at)
                SELECT 'ses_' || s, 'ten_' || (s % 5 + 1), 'prj_1', 'agv_1', 'env_1', 1, 'idle', now(), now(), now()
                  FROM generate_series(1, 2000) s
                """);
        DbAssertions.update("""
                INSERT INTO rt.threads (id, tenant_id, project_id, session_id, agent_name, status, created_at, updated_at)
                SELECT 'thr_' || s, 'ten_' || (s % 5 + 1), 'prj_1', 'ses_' || s, 'assistant', 'idle', now(), now()
                  FROM generate_series(1, 2000) s
                """);
        DbAssertions.update("""
                INSERT INTO rt.turns (id, tenant_id, project_id, session_id, thread_id, kind, status, not_before,
                                      lease_epoch, created_at, updated_at)
                SELECT 'trn_done_' || s || '_' || n, 'ten_' || (s % 5 + 1), 'prj_1', 'ses_' || s, 'thr_' || s,
                       'messages', 'completed', now(), 1, now() - n * interval '1 minute', now()
                  FROM generate_series(1, 2000) s, generate_series(1, 10) n
                """);
        DbAssertions.update("""
                INSERT INTO rt.turns (id, tenant_id, project_id, session_id, thread_id, kind, status, not_before,
                                      created_at, updated_at)
                SELECT 'trn_queued_' || s, 'ten_' || (s % 5 + 1), 'prj_1', 'ses_' || s, 'thr_' || s,
                       'messages', 'queued', now() - interval '1 day', now() - s * interval '1 second', now()
                  FROM generate_series(1, 50) s
                """);
        DbAssertions.update("""
                INSERT INTO rt.turns (id, tenant_id, project_id, session_id, thread_id, kind, status, not_before,
                                      lease_owner, lease_epoch, lease_expires_at, created_at, updated_at)
                SELECT 'trn_running_' || s, 'ten_' || (s % 5 + 1), 'prj_1', 'ses_' || s, 'thr_' || s,
                       'messages', 'running', now(), 'node-a', 1, now() + interval '30 seconds', now(), now()
                  FROM generate_series(51, 70) s
                """);
        DbAssertions.update("ANALYZE rt.turns");
        DbAssertions.update("ANALYZE rt.tenant_concurrency");
    }

    @Test
    void claimSelectsThroughThePartialQueuedIndex() {
        List<String> plan = explain(
                TurnQueueRepository.CLAIM_CANDIDATE_SQL,
                List.of(JdbcValues.timestamptz(NOW), new String[] {"thr_1", "thr_2"}),
                List.of("now", "excluded"));

        assertThat(String.join("\n", plan))
                .contains("LockRows")
                .contains("Index Scan using turns_queued_idx on turns t")
                .doesNotContain("Seq Scan on turns");
    }

    @Test
    void reaperFindsExpiredLeasesThroughTheExpiryIndex() {
        List<String> plan = explain("""
                SELECT id, lease_owner, lease_epoch, lease_expires_at FROM rt.turns
                 WHERE status = 'running' AND lease_expires_at < :now
                 ORDER BY lease_expires_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, List.of(JdbcValues.timestamptz(NOW)), List.of("now"));

        assertThat(String.join("\n", plan)).contains("turns_lease_expiry_idx").doesNotContain("Seq Scan on turns");
    }

    @Test
    void stateFenceLocksTheActiveTurnThroughAnIndex() {
        List<String> plan = explain("""
                SELECT tenant_id, project_id FROM rt.turns
                 WHERE thread_id = :thread AND status = 'running' AND lease_owner = :node AND lease_epoch = :epoch
                   FOR SHARE
                """, List.of("thr_60", "node-a", 1L), List.of("thread", "node", "epoch"));

        assertThat(String.join("\n", plan)).contains("Index Scan").doesNotContain("Seq Scan on turns");
    }

    private static List<String> explain(String sql, List<Object> values, List<String> names) {
        JdbcClient.StatementSpec statement = app.sql("EXPLAIN " + sql);
        for (int i = 0; i < names.size(); i++) {
            statement = statement.param(names.get(i), values.get(i));
        }
        List<String> plan = statement.query(String.class).list();
        log.info("EXPLAIN\n{}\n{}", sql.strip(), String.join("\n", plan));
        return plan;
    }
}
