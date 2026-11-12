package cn.refinex.meridian.controlplane.budget.app;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
import cn.refinex.meridian.controlplane.budget.infra.RedisBudgetStore;
import cn.refinex.meridian.controlplane.budget.infra.system.BudgetLedgerRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 限额的全量同步（ADR-0048 第 2 条）：逐个租户按数据库中的预算整体重写 Redis 的限额哈希。管理接口在提交后增量写入，这里兜住写入失败与
 * Redis 数据丢失；没有预算的租户哈希被删除，所有级别按 0 处理（新租户默认零预算）。多实例同时运行结果相同。
 */
@Service
public class BudgetLimitSync {

    private static final Logger log = LoggerFactory.getLogger(BudgetLimitSync.class);

    private final BudgetLedgerRepository ledger;
    private final RedisBudgetStore redis;

    /**
     * 创建用例。
     *
     * @param ledger 跨租户的预算仓储
     * @param redis 限额哈希
     */
    public BudgetLimitSync(BudgetLedgerRepository ledger, RedisBudgetStore redis) {
        this.ledger = ledger;
        this.redis = redis;
    }

    /**
     * 重写全部租户的限额。
     *
     * @return 重写的租户数
     */
    public int syncAll() {
        int synced = 0;
        for (String tenantId : ledger.activeTenants()) {
            try {
                sync(tenantId);
                synced++;
            } catch (RuntimeException e) {
                log.warn("租户的预算限额同步失败 tenantId={}", tenantId, e);
            }
        }
        return synced;
    }

    /**
     * 重写一个租户的限额。
     *
     * @param tenantId 租户 ID
     */
    public void sync(String tenantId) {
        Map<String, String> limits = new LinkedHashMap<>();
        for (Budget budget : ledger.budgetsOf(tenantId)) {
            limits.put(
                    BudgetKeys.limitField(budget.level(), budget.scopeId()),
                    BudgetKeys.limitValue(budget.hardLimitTokens()));
        }
        redis.replaceLimits(tenantId, limits);
    }
}
