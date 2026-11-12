package cn.refinex.meridian.controlplane.budget.app;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.controlplane.budget.infra.RedisBudgetStore;
import cn.refinex.meridian.controlplane.budget.infra.system.BudgetLedgerRepository;
import cn.refinex.meridian.controlplane.budget.infra.system.BudgetLedgerRepository.LedgerTotal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 预算计数的对账（架构设计「计量与预算」，ADR-0048 第 5 条）：以账本为准修正 Redis 中的已用计数，目标值为"账本汇总加未过期的预扣"。
 * 修正用增量，不覆盖并发的预扣与结算；每次修正写 {@code cp.budget_corrections} 并计指标，修正量偏大时记 WARN（预扣逻辑可能有误）。
 *
 * <p>候选：本月有账本汇总的范围、最近 {@link #SESSION_WINDOW} 有用量的会话，以及 Redis 中本月与会话的计数键（账本中没有的按 0，
 * 例如结算前失败、预扣过期留下的计数）。多实例经咨询锁只运行一个，锁在本次对账的事务结束时释放。
 */
@Service
public class BudgetReconciliation {

    /** 修正次数的指标：按级别与方向（{@code up} 补回、{@code down} 扣除）。 */
    public static final String CORRECTIONS = "meridian.budget.reconciliation.corrections";

    /** 修正量（token，绝对值）的分布：按级别。 */
    public static final String CORRECTION_TOKENS = "meridian.budget.reconciliation.correction.tokens";

    /** 会话的计数不分周期，只对账最近有用量的。 */
    static final Duration SESSION_WINDOW = Duration.ofHours(24);

    /** 修正量偏大的判断：超过账本的 10% 且超过这个绝对值。 */
    static final long LARGE_CORRECTION_TOKENS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(BudgetReconciliation.class);

    private final BudgetLedgerRepository ledger;
    private final RedisBudgetStore redis;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param ledger 跨租户的预算仓储
     * @param redis 计数与预扣
     * @param transactions 事务模板
     * @param meters 指标
     * @param clock 时钟
     */
    public BudgetReconciliation(
            BudgetLedgerRepository ledger,
            RedisBudgetStore redis,
            TransactionTemplate transactions,
            MeterRegistry meters,
            Clock clock) {
        this.ledger = ledger;
        this.redis = redis;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * 执行一次对账。
     *
     * @return 结果；别的实例正在对账时 {@link Result#ran()} 为 false
     */
    public Result reconcile() {
        Result result = transactions.execute(status -> {
            if (!ledger.tryReconciliationLock()) {
                return new Result(false, 0, 0);
            }
            return run(clock.instant());
        });
        return result == null ? new Result(false, 0, 0) : result;
    }

    private Result run(Instant now) {
        String month = BudgetKeys.month(now);
        Map<String, LedgerTotal> targets = new LinkedHashMap<>();
        for (LedgerTotal total : ledger.reconciliationCandidates(month, now.minus(SESSION_WINDOW))) {
            targets.put(BudgetKeys.used(total.tenantId(), total.level(), total.scopeId(), total.period()), total);
        }
        for (String key : redis.usedKeys()) {
            if (targets.containsKey(key)) {
                continue;
            }
            Optional<BudgetKeys.UsedKey> parsed = BudgetKeys.parseUsed(key);
            if (parsed.isEmpty()) {
                continue;
            }
            BudgetKeys.UsedKey used = parsed.get();
            if (!used.period().equals(month) && !BudgetKeys.TOTAL_PERIOD.equals(used.period())) {
                continue;
            }
            targets.put(
                    key,
                    new LedgerTotal(
                            used.tenantId(),
                            used.level(),
                            used.scopeId(),
                            used.period(),
                            ledger.usage(used.tenantId(), used.level(), used.scopeId(), used.period())));
        }
        Map<String, Map<String, Long>> inflightByTenant = new HashMap<>();
        int corrected = 0;
        for (Map.Entry<String, LedgerTotal> entry : targets.entrySet()) {
            LedgerTotal total = entry.getValue();
            long inflight = inflightByTenant
                    .computeIfAbsent(total.tenantId(), redis::inflight)
                    .getOrDefault(entry.getKey(), 0L);
            long observed = redis.used(entry.getKey());
            long correction = total.usedTokens() + inflight - observed;
            if (correction == 0) {
                continue;
            }
            redis.adjust(entry.getKey(), correction);
            ledger.insertCorrection(
                    total.tenantId(),
                    total.level(),
                    total.scopeId(),
                    total.period(),
                    observed,
                    inflight,
                    total.usedTokens(),
                    correction,
                    now);
            record(total, correction, observed, inflight);
            corrected++;
        }
        return new Result(true, targets.size(), corrected);
    }

    private void record(LedgerTotal total, long correction, long observed, long inflight) {
        BudgetLevel level = total.level();
        Counter.builder(CORRECTIONS)
                .description("预算对账修正 Redis 计数的次数")
                .tag("level", level.value())
                .tag("direction", correction > 0 ? "up" : "down")
                .register(meters)
                .increment();
        DistributionSummary.builder(CORRECTION_TOKENS)
                .description("预算对账的修正量（token，绝对值）")
                .baseUnit("tokens")
                .tag("level", level.value())
                .register(meters)
                .record(Math.abs(correction));
        long magnitude = Math.abs(correction);
        if (magnitude > LARGE_CORRECTION_TOKENS && magnitude * 10 > total.usedTokens()) {
            log.warn(
                    "预算对账的修正量偏大，预扣或结算可能有误 tenantId={} level={} scopeId={} period={} observed={} inflight={}"
                            + " ledger={} correction={}",
                    total.tenantId(),
                    level.value(),
                    total.scopeId(),
                    total.period(),
                    observed,
                    inflight,
                    total.usedTokens(),
                    correction);
        } else {
            log.info(
                    "预算对账修正 tenantId={} level={} scopeId={} period={} correction={}",
                    total.tenantId(),
                    level.value(),
                    total.scopeId(),
                    total.period(),
                    correction);
        }
    }

    /**
     * 一次对账的结果。
     *
     * @param ran 是否执行（拿到了锁）
     * @param checked 检查的计数个数
     * @param corrected 修正的计数个数
     */
    public record Result(boolean ran, int checked, int corrected) {}
}
