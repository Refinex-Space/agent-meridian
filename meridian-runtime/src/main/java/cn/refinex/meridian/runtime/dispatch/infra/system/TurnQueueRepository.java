package cn.refinex.meridian.runtime.dispatch.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 轮次队列的跨租户仓储：入队（计数行、轮次、输入）与认领的三步 SQL（架构设计「一轮执行的生命周期」中的「认领」）。
 * 方法都在调用方的事务中执行，事务边界由 {@code dispatch.app} 定义。
 *
 * <p>锁顺序：认领先锁 {@code rt.turns} 再锁 {@code rt.tenant_concurrency}；入队先写计数行再写轮次，同一线程的并发入队只会
 * 等待先到者提交，不会互相等待。
 */
@Repository
@CrossTenant(reason = "调度：入队时为租户创建并发计数行，认领时跨租户选取可执行的轮次并占用租户名额")
public class TurnQueueRepository extends SystemRepository {

    /** 未结束的轮次状态，与部分唯一索引 turns_thread_active_uidx 的条件一致，ON CONFLICT 按它推断冲突目标。 */
    private static final String ACTIVE_STATUSES = "('queued', 'running', 'awaiting_action')";

    /**
     * 认领第 1 步，架构设计「认领」中的 SQL：时间取注入的 {@code Clock}（{@code :now}），并排除本节点仍持有的线程。
     * 执行计划走部分索引 {@code turns_queued_idx}（{@code ClaimQueryPlanIT} 断言，结果见模块 {@code CLAUDE.md}）。
     */
    static final String CLAIM_CANDIDATE_SQL = """
            SELECT t.id, t.tenant_id, t.thread_id
              FROM rt.turns t
              JOIN rt.tenant_concurrency c ON c.tenant_id = t.tenant_id
             WHERE t.status = 'queued' AND t.not_before <= :now AND c.running < c.max_running
               AND NOT (t.thread_id = ANY (:excluded))
             ORDER BY t.priority, t.created_at
               FOR UPDATE OF t SKIP LOCKED
             LIMIT 1
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public TurnQueueRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 租户第一次入队时创建并发计数行；已存在时什么都不做（架构设计「认领」中的租户并发计数行）。没有计数行的轮次在认领的
     * 第 1 步就被关联条件排除，永远不会被认领，所以计数行必须与轮次在同一事务中写入。
     *
     * @param tenantId 租户 ID
     * @param maxRunning 新建时的并发上限
     * @param now 当前时间
     */
    public void ensureTenantCounter(String tenantId, int maxRunning, Instant now) {
        jdbc().sql("""
                        INSERT INTO rt.tenant_concurrency (tenant_id, running, max_running, updated_at)
                        VALUES (:tenant_id, 0, :max_running, :now)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """)
                .param("tenant_id", tenantId)
                .param("max_running", maxRunning)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 插入一个待认领的轮次。线程已有未结束的轮次时，部分唯一索引使插入成为空操作。
     *
     * @param turnId 新轮次 ID
     * @param turn 入队参数
     * @param now 当前时间，同时作为入队时间与最早认领时间
     * @return 插入成功时为 true；线程已有未结束的轮次时为 false
     */
    public boolean insertQueued(String turnId, NewTurn turn, Instant now) {
        return jdbc().sql("""
                                INSERT INTO rt.turns (id, tenant_id, project_id, session_id, thread_id, kind, status,
                                                      priority, not_before, created_at, updated_at)
                                VALUES (:id, :tenant_id, :project_id, :session_id, :thread_id, :kind, 'queued',
                                        :priority, :now, :now, :now)
                                ON CONFLICT (thread_id) WHERE status IN %s DO NOTHING
                                """.formatted(ACTIVE_STATUSES))
                        .param("id", turnId)
                        .param("tenant_id", turn.tenantId())
                        .param("project_id", turn.projectId())
                        .param("session_id", turn.sessionId())
                        .param("thread_id", turn.threadId())
                        .param("kind", turn.input().kind())
                        .param("priority", turn.priority())
                        .param("now", JdbcValues.timestamptz(now))
                        .update()
                == 1;
    }

    /**
     * 写入轮次的完整输入。
     *
     * @param turnId 轮次 ID
     * @param turn 入队参数
     * @param payload 输入 JSON
     * @param now 当前时间
     */
    public void insertInput(String turnId, NewTurn turn, String payload, Instant now) {
        jdbc().sql("""
                        INSERT INTO rt.turn_inputs (turn_id, tenant_id, project_id, payload, created_at)
                        VALUES (:turn_id, :tenant_id, :project_id, :payload, :now)
                        """)
                .param("turn_id", turnId)
                .param("tenant_id", turn.tenantId())
                .param("project_id", turn.projectId())
                .param("payload", JdbcValues.jsonb(payload))
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 认领第 1 步：选出并锁住一个可执行的轮次。满额租户的轮次被关联条件排除；被其他事务锁住的轮次跳过；
     * 本节点仍在执行的线程被排除（架构设计「同节点重新认领」）。
     *
     * @param now 当前时间，与 {@code not_before} 比较
     * @param excludedThreads 本节点仍持有的线程，不认领它们
     * @return 被锁住的候选轮次；没有可执行的轮次时为空
     */
    public Optional<Candidate> lockNextClaimable(Instant now, Collection<String> excludedThreads) {
        return jdbc().sql(CLAIM_CANDIDATE_SQL)
                .param("now", JdbcValues.timestamptz(now))
                .param("excluded", JdbcValues.textArray(excludedThreads))
                .query((rs, row) ->
                        new Candidate(rs.getString("id"), rs.getString("tenant_id"), rs.getString("thread_id")))
                .optional();
    }

    /**
     * 认领第 2 步：占用租户并发名额。
     *
     * @param tenantId 租户 ID
     * @param now 当前时间
     * @return 占用成功时为 true；名额已满（与其他 Worker 竞争失败）时为 false，调用方必须回滚整个认领事务
     */
    public boolean acquireTenantSlot(String tenantId, Instant now) {
        return jdbc().sql("""
                                UPDATE rt.tenant_concurrency SET running = running + 1, updated_at = :now
                                 WHERE tenant_id = :tenant_id AND running < max_running
                                """)
                        .param("now", JdbcValues.timestamptz(now))
                        .param("tenant_id", tenantId)
                        .update()
                == 1;
    }

    /**
     * 认领第 3 步：标记轮次为执行中，epoch 加一，设置租约到期时间。轮次已在第 1 步被本事务锁住。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param now 当前时间
     * @param leaseExpiresAt 租约到期时间
     * @return 更新后的轮次
     * @throws IllegalStateException 轮次不再是 queued 时（第 1 步的行锁保证不会发生）
     */
    public ClaimedTurn markRunning(String turnId, String nodeId, Instant now, Instant leaseExpiresAt) {
        return jdbc().sql("""
                        UPDATE rt.turns
                           SET status = 'running', lease_owner = :node_id, lease_epoch = lease_epoch + 1,
                               lease_expires_at = :lease_expires_at, claim_count = claim_count + 1,
                               claimed_at = :now, updated_at = :now
                         WHERE id = :turn_id AND status = 'queued'
                        RETURNING id, tenant_id, project_id, session_id, thread_id, kind, lease_epoch, claim_count,
                                  recovery_attempt, interrupt_requested_at, created_at, claimed_at, lease_expires_at
                        """)
                .param("node_id", nodeId)
                .param("lease_expires_at", JdbcValues.timestamptz(leaseExpiresAt))
                .param("now", JdbcValues.timestamptz(now))
                .param("turn_id", turnId)
                .query((rs, row) -> new ClaimedTurn(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("session_id"),
                        rs.getString("thread_id"),
                        rs.getString("kind"),
                        rs.getLong("lease_epoch"),
                        rs.getInt("claim_count"),
                        rs.getInt("recovery_attempt"),
                        JdbcValues.instant(rs, "interrupt_requested_at"),
                        JdbcValues.instant(rs, "created_at"),
                        JdbcValues.instant(rs, "claimed_at"),
                        JdbcValues.instant(rs, "lease_expires_at")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("认领时轮次已不是 queued：" + turnId));
    }

    /**
     * 读取轮次的完整输入。
     *
     * @param turnId 轮次 ID
     * @return 输入 JSON；不存在时为空
     */
    public Optional<String> findInput(String turnId) {
        return jdbc().sql("SELECT payload FROM rt.turn_inputs WHERE turn_id = :turn_id")
                .param("turn_id", turnId)
                .query(String.class)
                .optional();
    }

    /**
     * 统计等待认领的轮次数（指标 {@code meridian.dispatch.queue.depth}），走部分索引 {@code turns_queued_idx}。
     *
     * @return {@code status='queued'} 的轮次数
     */
    public long countQueued() {
        return jdbc().sql("SELECT count(*) FROM rt.turns WHERE status = 'queued'")
                .query(Long.class)
                .single();
    }

    /**
     * 认领第 1 步选出的候选轮次。
     *
     * @param turnId 轮次 ID
     * @param tenantId 租户 ID
     * @param threadId 线程 ID
     */
    public record Candidate(String turnId, String tenantId, String threadId) {}
}
