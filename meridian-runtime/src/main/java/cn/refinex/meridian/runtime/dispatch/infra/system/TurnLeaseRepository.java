package cn.refinex.meridian.runtime.dispatch.infra.system;

import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 租约的跨租户仓储：续租、回收、置回队列、结束轮次与释放租户名额（架构设计「租约」「租约回收」「置回队列的三种情况」）。
 *
 * <p>每条更新都带租约条件，以可空结果报告"0 行更新"，由 {@code dispatch.app} 转成明确的结果类型，不吞掉租约丢失。
 */
@Repository
@CrossTenant(reason = "租约：续租、置回队列与结束按轮次 ID、节点与 epoch 校验，回收器跨租户扫描过期租约")
public class TurnLeaseRepository extends SystemRepository {

    private static final String RELEASED_COLUMNS = "id, tenant_id, session_id, thread_id, recovery_attempt";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public TurnLeaseRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 续租：把到期时间推迟到 {@code leaseExpiresAt}，同时读出中断请求。租约已过期但还没被回收时仍可续上，因为没有别的节点持有它。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param epoch 本次执行的 epoch
     * @param leaseExpiresAt 新的到期时间
     * @param now 当前时间
     * @return 续租结果；租约条件不满足（已被回收或被其他节点认领）时为空
     */
    public Optional<Renewal> renew(String turnId, String nodeId, long epoch, Instant leaseExpiresAt, Instant now) {
        return jdbc().sql("""
                        UPDATE rt.turns SET lease_expires_at = :lease_expires_at, updated_at = :now
                         WHERE id = :turn_id AND status = 'running' AND lease_owner = :node_id AND lease_epoch = :epoch
                        RETURNING interrupt_requested_at
                        """)
                .param("lease_expires_at", JdbcValues.timestamptz(leaseExpiresAt))
                .param("now", JdbcValues.timestamptz(now))
                .param("turn_id", turnId)
                .param("node_id", nodeId)
                .param("epoch", epoch)
                .query((rs, row) -> new Renewal(JdbcValues.instant(rs, "interrupt_requested_at")))
                .optional();
    }

    /**
     * 回收器：锁住一个租约已过期的执行中轮次，跳过被其他回收器或写入方锁住的行。
     *
     * @param now 当前时间
     * @return 过期的租约；没有时为空
     */
    public Optional<ExpiredLease> lockNextExpired(Instant now) {
        return jdbc().sql("""
                        SELECT id, lease_owner, lease_epoch, lease_expires_at
                          FROM rt.turns
                         WHERE status = 'running' AND lease_expires_at < :now
                         ORDER BY lease_expires_at
                           FOR UPDATE SKIP LOCKED
                         LIMIT 1
                        """)
                .param("now", JdbcValues.timestamptz(now))
                .query((rs, row) -> new ExpiredLease(
                        rs.getString("id"),
                        rs.getString("lease_owner"),
                        rs.getLong("lease_epoch"),
                        JdbcValues.instant(rs, "lease_expires_at")))
                .optional();
    }

    /**
     * 持有者把自己的轮次置回队列（可重试错误、节点下线）。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param epoch 本次执行的 epoch
     * @param countsAsRecovery 是否让 {@code recovery_attempt} 加一
     * @param now 当前时间，同时作为最早可被重新认领的时间
     * @return 被释放的轮次；租约条件不满足时为空
     */
    public Optional<ReleasedTurn> requeueOwned(
            String turnId, String nodeId, long epoch, boolean countsAsRecovery, Instant now) {
        return jdbc().sql("""
                        UPDATE rt.turns
                           SET status = 'queued', lease_expires_at = NULL, not_before = :now,
                               recovery_attempt = recovery_attempt + :increment, updated_at = :now
                         WHERE id = :turn_id AND status = 'running' AND lease_owner = :node_id AND lease_epoch = :epoch
                        RETURNING %s
                        """.formatted(RELEASED_COLUMNS))
                .param("now", JdbcValues.timestamptz(now))
                .param("increment", countsAsRecovery ? 1 : 0)
                .param("turn_id", turnId)
                .param("node_id", nodeId)
                .param("epoch", epoch)
                .query(TurnLeaseRepository::mapReleased)
                .optional();
    }

    /**
     * 回收器把租约过期的轮次置回队列，{@code recovery_attempt} 加一。轮次已由 {@link #lockNextExpired} 在本事务中锁住。
     *
     * @param turnId 轮次 ID
     * @param epoch 过期租约的 epoch
     * @param now 当前时间，同时作为最早可被重新认领的时间
     * @return 被释放的轮次；轮次已不在该 epoch 执行时为空
     */
    public Optional<ReleasedTurn> requeueExpired(String turnId, long epoch, Instant now) {
        return jdbc().sql("""
                        UPDATE rt.turns
                           SET status = 'queued', lease_expires_at = NULL, not_before = :now,
                               recovery_attempt = recovery_attempt + 1, updated_at = :now
                         WHERE id = :turn_id AND status = 'running' AND lease_epoch = :epoch
                        RETURNING %s
                        """.formatted(RELEASED_COLUMNS))
                .param("now", JdbcValues.timestamptz(now))
                .param("turn_id", turnId)
                .param("epoch", epoch)
                .query(TurnLeaseRepository::mapReleased)
                .optional();
    }

    /**
     * 持有者结束轮次。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param epoch 本次执行的 epoch
     * @param outcome 结束后的状态
     * @param stopReason 停止原因；可为空，表示不以 {@code session.status_idle} 结束
     * @param now 当前时间
     * @return 被释放的轮次；租约条件不满足时为空
     */
    public Optional<ReleasedTurn> finish(
            String turnId, String nodeId, long epoch, TurnOutcome outcome, StopReason stopReason, Instant now) {
        return jdbc().sql("""
                        UPDATE rt.turns
                           SET status = :status, stop_reason = :stop_reason, lease_expires_at = NULL,
                               finished_at = :now, updated_at = :now
                         WHERE id = :turn_id AND status = 'running' AND lease_owner = :node_id AND lease_epoch = :epoch
                        RETURNING %s
                        """.formatted(RELEASED_COLUMNS))
                .param("status", outcome.value())
                .param("stop_reason", stopReason == null ? null : stopReason.value())
                .param("now", JdbcValues.timestamptz(now))
                .param("turn_id", turnId)
                .param("node_id", nodeId)
                .param("epoch", epoch)
                .query(TurnLeaseRepository::mapReleased)
                .optional();
    }

    /**
     * 释放租户的一个并发名额。计数已为 0 说明计数与轮次不一致，返回 false 由调用方记录，不让计数变成负数。
     *
     * @param tenantId 租户 ID
     * @param now 当前时间
     * @return 释放成功时为 true；计数已为 0 或计数行不存在时为 false
     */
    public boolean releaseTenantSlot(String tenantId, Instant now) {
        return jdbc().sql("""
                                UPDATE rt.tenant_concurrency SET running = running - 1, updated_at = :now
                                 WHERE tenant_id = :tenant_id AND running > 0
                                """)
                        .param("now", JdbcValues.timestamptz(now))
                        .param("tenant_id", tenantId)
                        .update()
                == 1;
    }

    /**
     * 锁定并读取会话的全部未结束轮次（{@code queued}、{@code running}、{@code awaiting_action}）。锁住后认领会跳过它们
     * （{@code SKIP LOCKED}），回收与结束要等本事务提交，调用方据此判断会话能否归档。
     *
     * @param sessionId 会话 ID
     * @return 未结束的轮次及其状态
     */
    public List<ActiveTurn> lockActive(String sessionId) {
        return jdbc().sql("""
                        SELECT id, tenant_id, status FROM rt.turns
                         WHERE session_id = :session_id AND status IN ('queued', 'running', 'awaiting_action')
                           FOR UPDATE
                        """)
                .param("session_id", sessionId)
                .query((rs, row) ->
                        new ActiveTurn(rs.getString("id"), rs.getString("tenant_id"), rs.getString("status")))
                .list();
    }

    /**
     * 取消会话的全部未结束轮次：会话归档或删除时调用（架构设计「状态机」「消息投递」中"删除会话：取消当前轮"）。
     * 执行中的轮次失去租约条件，持有它的节点在下一次带租约的写入或续租时放弃本轮，不再写入任何数据。
     *
     * @param sessionId 会话 ID
     * @param now 当前时间
     * @return 被取消的轮次；{@code running()} 为 true 的需要释放租户并发名额
     */
    public List<ActiveTurn> cancelActive(String sessionId, Instant now) {
        return jdbc().sql("""
                        WITH active AS (
                            SELECT id, status FROM rt.turns
                             WHERE session_id = :session_id AND status IN ('queued', 'running', 'awaiting_action')
                               FOR UPDATE
                        )
                        UPDATE rt.turns t
                           SET status = 'cancelled', lease_expires_at = NULL, finished_at = :now, updated_at = :now
                          FROM active a
                         WHERE t.id = a.id
                        RETURNING t.id, t.tenant_id, a.status AS previous_status
                        """)
                .param("session_id", sessionId)
                .param("now", JdbcValues.timestamptz(now))
                .query((rs, row) ->
                        new ActiveTurn(rs.getString("id"), rs.getString("tenant_id"), rs.getString("previous_status")))
                .list();
    }

    private static ReleasedTurn mapReleased(ResultSet rs, int row) throws SQLException {
        return new ReleasedTurn(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("session_id"),
                rs.getString("thread_id"),
                rs.getInt("recovery_attempt"));
    }

    /**
     * 一次成功的续租。
     *
     * @param interruptRequestedAt 中断请求时间；可为空，表示没有中断请求
     */
    public record Renewal(Instant interruptRequestedAt) {}

    /**
     * 过期的租约。
     *
     * @param turnId 轮次 ID
     * @param leaseOwner 过期时的持有者
     * @param epoch 过期租约的 epoch
     * @param leaseExpiresAt 到期时间
     */
    public record ExpiredLease(String turnId, String leaseOwner, long epoch, Instant leaseExpiresAt) {}

    /**
     * 不再执行、已释放租约的轮次。
     *
     * @param turnId 轮次 ID
     * @param tenantId 租户 ID，用于释放并发名额
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param recoveryAttempt 更新后的恢复次数
     */
    public record ReleasedTurn(
            String turnId, String tenantId, String sessionId, String threadId, int recoveryAttempt) {}

    /**
     * 一个未结束的轮次。
     *
     * @param turnId 轮次 ID
     * @param tenantId 租户 ID，用于释放并发名额
     * @param status 状态：{@code queued}、{@code running} 或 {@code awaiting_action}；取消结果中为取消前的状态
     */
    public record ActiveTurn(String turnId, String tenantId, String status) {

        /**
         * 是否正在执行（占用租户并发名额）。
         *
         * @return 状态为 running 时为 true
         */
        public boolean running() {
            return "running".equals(status);
        }
    }
}
