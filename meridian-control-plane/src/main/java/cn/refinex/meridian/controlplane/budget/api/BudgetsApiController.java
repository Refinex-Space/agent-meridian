package cn.refinex.meridian.controlplane.budget.api;

import cn.refinex.meridian.api.admin.model.Budget;
import cn.refinex.meridian.api.admin.model.BudgetAlert;
import cn.refinex.meridian.api.admin.model.BudgetAlertPage;
import cn.refinex.meridian.api.admin.model.BudgetLevel;
import cn.refinex.meridian.api.admin.model.BudgetPage;
import cn.refinex.meridian.api.admin.model.CreateBudgetRequest;
import cn.refinex.meridian.api.admin.model.ProjectBudgetStatus;
import cn.refinex.meridian.api.admin.model.UpdateBudgetRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.budget.app.BudgetService;
import cn.refinex.meridian.controlplane.budget.app.BudgetService.BudgetView;
import cn.refinex.meridian.controlplane.openapi.admin.BudgetsApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 预算与预算告警的管理接口（Budgets 标签，ADR-0048）：只做授权声明、decode → call → map。 */
@RestController
public class BudgetsApiController implements BudgetsApi {

    private final BudgetService budgets;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param budgets 预算用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public BudgetsApiController(BudgetService budgets, AdminRequests requests, HttpServletRequest request) {
        this.budgets = budgets;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.BUDGET_WRITE)
    public ResponseEntity<Budget> createBudget(String tenantId, CreateBudgetRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Budget.class,
                () -> toDto(budgets.create(
                        actor(),
                        tenantId,
                        level(body.getLevel()),
                        body.getScopeId(),
                        body.getSoftLimitTokens(),
                        body.getHardLimitTokens())));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<BudgetPage> listBudgets(String tenantId, BudgetLevel level, Integer limit, String after) {
        var page = budgets.list(tenantId, level == null ? null : level(level), PageRequest.of(limit, after));
        return ResponseEntity.ok(new BudgetPage(
                page.data().stream().map(BudgetsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<Budget> getBudget(String tenantId, String budgetId) {
        return ResponseEntity.ok(toDto(budgets.get(tenantId, budgetId)));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_WRITE)
    public ResponseEntity<Budget> updateBudget(
            String tenantId, String budgetId, UpdateBudgetRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Budget.class,
                () -> toDto(budgets.update(
                        actor(), tenantId, budgetId, body.getSoftLimitTokens(), body.getHardLimitTokens())));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_WRITE)
    public ResponseEntity<Void> deleteBudget(String tenantId, String budgetId, String idempotencyKey) {
        return requests.delete(request, tenantId, idempotencyKey, () -> budgets.delete(actor(), tenantId, budgetId));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<BudgetAlertPage> listBudgetAlerts(String tenantId, Integer limit, String after) {
        var page = budgets.alerts(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new BudgetAlertPage(
                page.data().stream().map(BudgetsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    private static cn.refinex.meridian.common.budget.BudgetLevel level(BudgetLevel level) {
        return cn.refinex.meridian.common.budget.BudgetLevel.of(level.getValue());
    }

    private static Budget toDto(BudgetView view) {
        var budget = view.budget();
        return new Budget(
                budget.id(),
                budget.tenantId(),
                BudgetLevel.fromValue(budget.level().value()),
                budget.scopeId(),
                budget.projectId(),
                budget.softLimitTokens(),
                budget.hardLimitTokens(),
                Budget.PeriodEnum.fromValue(view.period()),
                view.currentPeriod(),
                view.usedTokens(),
                budget.createdBy(),
                Times.utc(budget.createdAt()),
                Times.utc(budget.updatedAt()));
    }

    private static BudgetAlert toDto(cn.refinex.meridian.controlplane.budget.domain.BudgetAlert alert) {
        return new BudgetAlert(
                alert.id(),
                BudgetLevel.fromValue(alert.level().value()),
                alert.scopeId(),
                alert.period(),
                BudgetAlert.KindEnum.fromValue(alert.kind()),
                alert.thresholdTokens(),
                alert.usedTokens(),
                Times.utc(alert.occurredAt()));
    }

    @Override
    @RequiresPermission(Permission.USAGE_READ)
    public ResponseEntity<ProjectBudgetStatus> getProjectBudgetStatus(String tenantId, String projectId) {
        BudgetService.ProjectBudgetStatus status = budgets.projectStatus(tenantId, projectId);
        return ResponseEntity.ok(new ProjectBudgetStatus(
                status.projectId(),
                status.period(),
                Times.utc(status.periodStart()),
                Times.utc(status.periodEnd()),
                status.usedTokens(),
                status.softLimitTokens(),
                status.hardLimitTokens(),
                ProjectBudgetStatus.LimitSourceEnum.fromValue(status.limitSource()),
                ProjectBudgetStatus.StateEnum.fromValue(status.state())));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }
}
