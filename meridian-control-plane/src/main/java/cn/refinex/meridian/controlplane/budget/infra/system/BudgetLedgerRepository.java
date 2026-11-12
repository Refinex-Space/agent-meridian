package cn.refinex.meridian.controlplane.budget.infra.system;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
import cn.refinex.meridian.controlplane.budget.domain.BudgetAlert;
import cn.refinex.meridian.controlplane.budget.infra.BudgetRepository;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 跨租户的预算仓储：用量消费者在入账事务中累加账本汇总、写告警；对账任务读候选与账本值、写修正记录；限额全量同步读全部预算。
 */
@Repository
@CrossTenant(reason = "用量消费者、对账与限额同步是跨租户的后台作业（架构设计「租户隔离」中 SystemRepository 一行）")
public class BudgetLedgerRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public BudgetLedgerRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 累加一级预算在一个周期的账本汇总。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     * @param tokens 增量
     * @param now 累加时间
     * @return 累加后的汇总
     */
    public long addUsage(String tenantId, BudgetLevel level, String scopeId, String period, long tokens, Instant now) {
        return jdbc().sql("""
                        INSERT INTO cp.budget_usage (tenant_id, level, scope_id, period, used_tokens, updated_at)
                        VALUES (:tenant, :level, :scope, :period, :tokens, :now)
                        ON CONFLICT (tenant_id, level, scope_id, period)
                        DO UPDATE SET used_tokens = budget_usage.used_tokens + EXCLUDED.used_tokens,
                                      updated_at = EXCLUDED.updated_at
                        RETURNING used_tokens
                        """)
                .param("tenant", tenantId)
                .param("level", level.value())
                .param("scope", scopeId)
                .param("period", period)
                .param("tokens", tokens)
                .param("now", JdbcValues.timestamptz(now))
                .query(Long.class)
                .single();
    }

    /**
     * 一级预算在一个周期的账本汇总。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     * @return 汇总；没有用量时为 0
     */
    public long usage(String tenantId, BudgetLevel level, String scopeId, String period) {
        return jdbc().sql("""
                        SELECT used_tokens FROM cp.budget_usage
                         WHERE tenant_id = :tenant AND level = :level AND scope_id = :scope AND period = :period
                        """)
                .param("tenant", tenantId)
                .param("level", level.value())
                .param("scope", scopeId)
                .param("period", period)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /**
     * 对账候选：本月的账本汇总，与最近有用量的会话。
     *
     * @param month 本月的周期
     * @param sessionsSince 会话汇总的最近累加时间不早于它
     * @return 账本汇总
     */
    public List<LedgerTotal> reconciliationCandidates(String month, Instant sessionsSince) {
        return jdbc().sql("""
                        SELECT tenant_id, level, scope_id, period, used_tokens FROM cp.budget_usage
                         WHERE period = :month OR (level = 'session' AND updated_at >= :since)
                        """)
                .param("month", month)
                .param("since", JdbcValues.timestamptz(sessionsSince))
                .query((rs, row) -> new LedgerTotal(
                        rs.getString("tenant_id"),
                        BudgetLevel.of(rs.getString("level")),
                        rs.getString("scope_id"),
                        rs.getString("period"),
                        rs.getLong("used_tokens")))
                .list();
    }

    /**
     * 本次事务内取得对账锁：多实例中只有一个运行对账。
     *
     * @return 取得时为 true
     */
    public boolean tryReconciliationLock() {
        return Boolean.TRUE.equals(jdbc().sql("SELECT pg_try_advisory_xact_lock(hashtext('cp.budget_reconciliation'))")
                .query(Boolean.class)
                .single());
    }

    /**
     * 写一条对账修正记录。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     * @param observed Redis 中的计数
     * @param inflight 未过期的预扣
     * @param ledger 账本汇总
     * @param correction 修正量
     * @param now 对账时间
     */
    public void insertCorrection(
            String tenantId,
            BudgetLevel level,
            String scopeId,
            String period,
            long observed,
            long inflight,
            long ledger,
            long correction,
            Instant now) {
        jdbc().sql("""
                        INSERT INTO cp.budget_corrections (tenant_id, level, scope_id, period, observed_tokens, inflight_tokens,
                                                           ledger_tokens, correction_tokens, reconciled_at)
                        VALUES (:tenant, :level, :scope, :period, :observed, :inflight, :ledger, :correction, :now)
                        """)
                .param("tenant", tenantId)
                .param("level", level.value())
                .param("scope", scopeId)
                .param("period", period)
                .param("observed", observed)
                .param("inflight", inflight)
                .param("ledger", ledger)
                .param("correction", correction)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 一笔用量涉及的预算：租户级、各级的默认值，以及给出的具体范围。
     *
     * @param tenantId 租户 ID
     * @param scopeIds 具体范围 ID
     * @return 预算
     */
    public List<Budget> budgetsFor(String tenantId, Collection<String> scopeIds) {
        return jdbc().sql("""
                        SELECT * FROM cp.budgets
                         WHERE tenant_id = :tenant AND (scope_id IS NULL OR scope_id = ANY(:scopes))
                        """)
                .param("tenant", tenantId)
                .param("scopes", JdbcValues.textArray(scopeIds))
                .query(BudgetRepository::map)
                .list();
    }

    /**
     * 写一条告警；同一范围、周期、种类与阈值已有时不写。
     *
     * @param alert 告警
     * @return 真正写入时为 true
     */
    public boolean insertAlert(BudgetAlert alert) {
        return jdbc().sql("""
                        INSERT INTO cp.budget_alerts (id, tenant_id, level, scope_id, period, kind, threshold_tokens, used_tokens,
                                                      occurred_at)
                        VALUES (:id, :tenant, :level, :scope, :period, :kind, :threshold, :used, :occurred_at)
                        ON CONFLICT (tenant_id, level, scope_id, period, kind, threshold_tokens) DO NOTHING
                        """)
                        .param("id", alert.id())
                        .param("tenant", alert.tenantId())
                        .param("level", alert.level().value())
                        .param("scope", alert.scopeId())
                        .param("period", alert.period())
                        .param("kind", alert.kind())
                        .param("threshold", alert.thresholdTokens())
                        .param("used", alert.usedTokens())
                        .param("occurred_at", JdbcValues.timestamptz(alert.occurredAt()))
                        .update()
                == 1;
    }

    /**
     * 全部未删除的租户，限额全量同步逐个重写。
     *
     * @return 租户 ID
     */
    public List<String> activeTenants() {
        return jdbc().sql("SELECT id FROM cp.tenants WHERE deleted_at IS NULL ORDER BY id")
                .query(String.class)
                .list();
    }

    /**
     * 一个租户的全部预算。
     *
     * @param tenantId 租户 ID
     * @return 预算
     */
    public List<Budget> budgetsOf(String tenantId) {
        return jdbc().sql("SELECT * FROM cp.budgets WHERE tenant_id = :tenant ORDER BY id")
                .param("tenant", tenantId)
                .query(BudgetRepository::map)
                .list();
    }

    /**
     * 一级预算在一个周期的账本汇总。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     * @param usedTokens 汇总
     */
    public record LedgerTotal(String tenantId, BudgetLevel level, String scopeId, String period, long usedTokens) {}
}
