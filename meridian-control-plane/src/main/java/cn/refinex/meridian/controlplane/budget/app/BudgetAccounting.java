package cn.refinex.meridian.controlplane.budget.app;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
import cn.refinex.meridian.controlplane.budget.domain.BudgetAlert;
import cn.refinex.meridian.controlplane.budget.domain.BudgetCharge;
import cn.refinex.meridian.controlplane.budget.infra.system.BudgetLedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 账本汇总与软阈值告警（ADR-0048 第 3、4 条）：用量消费者在入账事务中调用，记录真正插入后才累加，重投不会重复计入。汇总越过软阈值
 * （之前小于、之后不小于）时写一条告警与审计 {@code budget.soft_limit_reached}，同一范围、周期与阈值只有一条。
 */
@Service
public class BudgetAccounting {

    /** 告警审计的操作者：control-plane 自己。 */
    static final String SERVICE = "control-plane";

    private final BudgetLedgerRepository ledger;
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param ledger 跨租户的预算仓储
     * @param audit 审计
     * @param clock 时钟
     */
    public BudgetAccounting(BudgetLedgerRepository ledger, AdminAudit audit, Clock clock) {
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 把一笔用量计入各级的账本汇总并检查软阈值。必须在入账事务中调用。
     *
     * @param charge 计入预算的用量
     */
    public void charge(BudgetCharge charge) {
        if (charge.tokens() <= 0) {
            return;
        }
        Instant now = clock.instant();
        List<BudgetCharge.Scope> scopes = charge.scopes();
        List<Budget> budgets = ledger.budgetsFor(
                charge.tenantId(),
                scopes.stream().map(BudgetCharge.Scope::scopeId).toList());
        for (BudgetCharge.Scope scope : scopes) {
            long after = ledger.addUsage(
                    charge.tenantId(), scope.level(), scope.scopeId(), scope.period(), charge.tokens(), now);
            long before = after - charge.tokens();
            Budget budget = effective(budgets, scope);
            if (budget == null || budget.softLimitTokens() == null) {
                continue;
            }
            long soft = budget.softLimitTokens();
            if (before < soft && after >= soft) {
                alert(charge, scope, budget, soft, after);
            }
        }
    }

    private void alert(BudgetCharge charge, BudgetCharge.Scope scope, Budget budget, long soft, long used) {
        BudgetAlert alert = new BudgetAlert(
                Ids.newId(IdPrefix.BUDGET_ALERT),
                charge.tenantId(),
                scope.level(),
                scope.scopeId(),
                scope.period(),
                BudgetAlert.SOFT_LIMIT_REACHED,
                soft,
                used,
                charge.occurredAt());
        if (!ledger.insertAlert(alert)) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("alert_id", alert.id());
        details.put("level", scope.level().value());
        details.put("scope_id", scope.scopeId());
        details.put("period", scope.period());
        details.put("threshold_tokens", soft);
        details.put("used_tokens", used);
        audit.recordService(
                "budget." + BudgetAlert.SOFT_LIMIT_REACHED,
                SERVICE,
                charge.tenantId(),
                scope.level() == BudgetLevel.TENANT ? null : charge.projectId(),
                "budget",
                budget.id(),
                details);
    }

    /** 一级范围生效的预算：具体范围的优先，其次是该级的默认值；租户级只有一条。 */
    private static Budget effective(List<Budget> budgets, BudgetCharge.Scope scope) {
        Budget fallback = null;
        for (Budget budget : budgets) {
            if (budget.level() != scope.level()) {
                continue;
            }
            if (scope.level() == BudgetLevel.TENANT || scope.scopeId().equals(budget.scopeId())) {
                return budget;
            }
            if (budget.scopeId() == null) {
                fallback = budget;
            }
        }
        return fallback;
    }
}
