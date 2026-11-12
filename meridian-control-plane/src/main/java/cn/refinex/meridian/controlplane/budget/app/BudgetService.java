package cn.refinex.meridian.controlplane.budget.app;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.apikey.app.ApiKeyDirectory;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
import cn.refinex.meridian.controlplane.budget.domain.BudgetAlert;
import cn.refinex.meridian.controlplane.budget.domain.BudgetErrorCode;
import cn.refinex.meridian.controlplane.budget.infra.BudgetAlertRepository;
import cn.refinex.meridian.controlplane.budget.infra.BudgetRepository;
import cn.refinex.meridian.controlplane.budget.infra.RedisBudgetStore;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.web.AfterCommit;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 预算的管理用例（ADR-0048 第 1、2 条）：设置、修改、删除一级预算，提交后把硬阈值写进 Redis 的限额哈希供 runtime 预扣时读取。
 * Redis 写入失败只记 WARN，{@link BudgetLimitSync} 定期全量重写兜底。
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgets;
    private final BudgetAlertRepository alerts;
    private final ProjectService projects;
    private final ApiKeyDirectory apiKeys;
    private final RedisBudgetStore redis;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param budgets 预算仓储
     * @param alerts 告警仓储
     * @param projects 项目用例，确认项目级范围存在
     * @param apiKeys API Key 查找，确认 API Key 级范围存在并取所在项目
     * @param redis 限额哈希
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public BudgetService(
            BudgetRepository budgets,
            BudgetAlertRepository alerts,
            ProjectService projects,
            ApiKeyDirectory apiKeys,
            RedisBudgetStore redis,
            AdminAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.budgets = budgets;
        this.alerts = alerts;
        this.projects = projects;
        this.apiKeys = apiKeys;
        this.redis = redis;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 设置一级预算。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID；为空表示该级的默认值
     * @param soft 软阈值；可为空
     * @param hard 硬阈值；为空表示不限
     * @return 预算与当前周期的用量
     * @throws ValidationException 范围或阈值不合法时
     * @throws ConflictException 同一级别、同一范围已有预算时
     */
    public BudgetView create(String actor, String tenantId, BudgetLevel level, String scopeId, Long soft, Long hard) {
        checkLimits(soft, hard);
        String projectId = scopeProject(tenantId, level, scopeId);
        Instant now = clock.instant();
        Budget budget = new Budget(
                Ids.newId(IdPrefix.BUDGET), tenantId, level, scopeId, projectId, soft, hard, actor, now, now);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    try {
                        budgets.insert(budget);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(BudgetErrorCode.EXISTS, "该范围已有预算，改用修改接口");
                    }
                    audit.record("budget.created", actor, tenantId, projectId, "budget", budget.id(), details(budget));
                    syncAfterCommit(budget);
                    return view(budget);
                }));
    }

    /**
     * 整体替换阈值。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 预算 ID
     * @param soft 软阈值；可为空
     * @param hard 硬阈值；为空表示不限
     * @return 修改后的预算
     */
    public BudgetView update(String actor, String tenantId, String id, Long soft, Long hard) {
        checkLimits(soft, hard);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Budget updated = require(id, true).withLimits(soft, hard, clock.instant());
                    budgets.updateLimits(updated);
                    audit.record(
                            "budget.updated", actor, tenantId, updated.projectId(), "budget", id, details(updated));
                    syncAfterCommit(updated);
                    return view(updated);
                }));
    }

    /**
     * 删除预算：范围回到同级的默认值，没有默认值时按 0 处理。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 预算 ID
     */
    public void delete(String actor, String tenantId, String id) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    Budget budget = require(id, true);
                    budgets.delete(id);
                    audit.record("budget.deleted", actor, tenantId, budget.projectId(), "budget", id, details(budget));
                    String field = BudgetKeys.limitField(budget.level(), budget.scopeId());
                    AfterCommit.run(() -> safely(tenantId, () -> redis.removeLimit(tenantId, field)));
                }));
    }

    /**
     * 读取预算。
     *
     * @param tenantId 租户 ID
     * @param id 预算 ID
     * @return 预算与当前周期的用量
     */
    public BudgetView get(String tenantId, String id) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> view(require(id, false)));
    }

    /**
     * 列出预算。
     *
     * @param tenantId 租户 ID
     * @param level 只列出这一级；可为空
     * @param page 分页参数
     * @return 一页预算
     */
    public PageResult<BudgetView> list(String tenantId, BudgetLevel level, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> PageResult.of(budgets.list(level, page), page, BudgetRepository::cursorOf)
                        .map(this::view));
    }

    /**
     * 列出预算告警，从新到旧。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页告警
     */
    public PageResult<BudgetAlert> alerts(String tenantId, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> PageResult.of(alerts.list(page), page, BudgetAlertRepository::cursorOf));
    }

    /** 范围所在的项目，同时确认范围合法：项目与 API Key 必须存在于租户中，会话 ID 格式正确。 */
    private String scopeProject(String tenantId, BudgetLevel level, String scopeId) {
        if (level == BudgetLevel.TENANT) {
            if (scopeId != null) {
                throw invalid("scope_id", "租户级预算不能给出范围 ID");
            }
            return null;
        }
        if (scopeId == null) {
            return null;
        }
        return switch (level) {
            case PROJECT -> projects.require(tenantId, scopeId).id();
            case API_KEY ->
                apiKeys.find(tenantId, scopeId)
                        .orElseThrow(
                                () -> new NotFoundException(BudgetErrorCode.SCOPE_NOT_FOUND, "API Key 不存在：" + scopeId))
                        .projectId();
            case SESSION -> {
                if (!Ids.isValid(scopeId, IdPrefix.SESSION)) {
                    throw invalid("scope_id", "会话 ID 格式错误：" + scopeId);
                }
                yield null;
            }
            case TENANT -> throw new IllegalStateException("租户级已在前面处理");
        };
    }

    private static void checkLimits(Long soft, Long hard) {
        try {
            Budget.checkLimits(soft, hard);
        } catch (IllegalArgumentException e) {
            throw invalid("soft_limit_tokens", e.getMessage());
        }
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException(BudgetErrorCode.INVALID, message, List.of(new FieldViolation(field, message)));
    }

    private Budget require(String id, boolean forUpdate) {
        return budgets.find(id, forUpdate)
                .orElseThrow(() -> new NotFoundException(BudgetErrorCode.NOT_FOUND, "预算不存在：" + id));
    }

    private BudgetView view(Budget budget) {
        String period = BudgetKeys.period(budget.level(), clock.instant());
        String scope = budget.level() == BudgetLevel.TENANT ? budget.tenantId() : budget.scopeId();
        Long used = scope == null ? null : budgets.usedTokens(budget.level(), scope, period);
        return new BudgetView(budget, budget.level().monthly() ? "monthly" : "total", period, used);
    }

    private void syncAfterCommit(Budget budget) {
        String field = BudgetKeys.limitField(budget.level(), budget.scopeId());
        String value = BudgetKeys.limitValue(budget.hardLimitTokens());
        String tenantId = budget.tenantId();
        AfterCommit.run(() -> safely(tenantId, () -> redis.putLimit(tenantId, field, value)));
    }

    private static void safely(String tenantId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("预算限额写入 Redis 失败，等待全量同步 tenantId={}", tenantId, e);
        }
    }

    private static Map<String, Object> details(Budget budget) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", budget.level().value());
        details.put("scope_id", budget.scopeId());
        details.put("soft_limit_tokens", budget.softLimitTokens());
        details.put("hard_limit_tokens", budget.hardLimitTokens());
        return details;
    }

    /**
     * 预算与当前周期。
     *
     * @param budget 预算
     * @param period 计数周期：{@code monthly} 或 {@code total}
     * @param currentPeriod 当前周期
     * @param usedTokens 当前周期的账本用量；默认值为空
     */
    public record BudgetView(Budget budget, String period, String currentPeriod, Long usedTokens) {}
}
