package cn.refinex.meridian.controlplane.policy.api;

import cn.refinex.meridian.api.admin.model.PermissionBehavior;
import cn.refinex.meridian.api.admin.model.PermissionRule;
import cn.refinex.meridian.api.admin.model.PermissionRulePage;
import cn.refinex.meridian.api.admin.model.PermissionRuleRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.PermissionRulesApi;
import cn.refinex.meridian.controlplane.policy.app.PolicyRuleService;
import cn.refinex.meridian.controlplane.policy.domain.PolicyRule;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户与项目策略的管理接口（PermissionRules 标签，ADR-0047）：租户策略与租户设置同权（查看 {@code TENANT_READ}、修改
 * {@code TENANT_UPDATE}），项目策略与智能体同权（{@code AGENT_READ}、{@code AGENT_WRITE}）。
 */
@RestController
public class PermissionRulesApiController implements PermissionRulesApi {

    private final PolicyRuleService rules;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param rules 策略规则用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public PermissionRulesApiController(PolicyRuleService rules, AdminRequests requests, HttpServletRequest request) {
        this.rules = rules;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.TENANT_UPDATE)
    public ResponseEntity<PermissionRule> createTenantPermissionRule(
            String tenantId, PermissionRuleRequest body, String idempotencyKey) {
        return create(tenantId, null, body, idempotencyKey);
    }

    @Override
    @RequiresPermission(Permission.TENANT_READ)
    public ResponseEntity<PermissionRulePage> listTenantPermissionRules(String tenantId, Integer limit, String after) {
        return list(tenantId, null, limit, after);
    }

    @Override
    @RequiresPermission(Permission.TENANT_UPDATE)
    public ResponseEntity<Void> deleteTenantPermissionRule(
            String tenantId, String permissionRuleId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> rules.delete(actor(), tenantId, null, permissionRuleId));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<PermissionRule> createProjectPermissionRule(
            String tenantId, String projectId, PermissionRuleRequest body, String idempotencyKey) {
        return create(tenantId, projectId, body, idempotencyKey);
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<PermissionRulePage> listProjectPermissionRules(
            String tenantId, String projectId, Integer limit, String after) {
        return list(tenantId, projectId, limit, after);
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteProjectPermissionRule(
            String tenantId, String projectId, String permissionRuleId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> rules.delete(actor(), tenantId, projectId, permissionRuleId));
    }

    private ResponseEntity<PermissionRule> create(
            String tenantId, String projectId, PermissionRuleRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                PermissionRule.class,
                () -> toDto(rules.create(
                        actor(),
                        tenantId,
                        projectId,
                        body.getToolName(),
                        body.getRuleContent(),
                        body.getBehavior().getValue())));
    }

    private ResponseEntity<PermissionRulePage> list(String tenantId, String projectId, Integer limit, String after) {
        var page = rules.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new PermissionRulePage(
                page.data().stream().map(PermissionRulesApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor()));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static PermissionRule toDto(PolicyRule rule) {
        return new PermissionRule()
                .id(rule.id())
                .tenantId(rule.tenantId())
                .projectId(rule.projectId())
                .toolName(rule.toolName())
                .ruleContent(rule.ruleContent())
                .behavior(PermissionBehavior.fromValue(rule.behavior()))
                .source(PermissionRule.SourceEnum.fromValue(rule.source()))
                .sourceActionId(rule.sourceActionId())
                .createdBy(rule.createdBy())
                .createdAt(Times.utc(rule.createdAt()));
    }
}
