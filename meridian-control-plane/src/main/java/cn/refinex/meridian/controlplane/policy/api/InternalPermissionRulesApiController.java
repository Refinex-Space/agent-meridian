package cn.refinex.meridian.controlplane.policy.api;

import cn.refinex.meridian.api.internal.model.InternalPermissionRule;
import cn.refinex.meridian.api.internal.model.ProjectPermissionRules;
import cn.refinex.meridian.api.internal.model.RememberRuleRequest;
import cn.refinex.meridian.controlplane.openapi.internal.InternalPermissionRulesApi;
import cn.refinex.meridian.controlplane.policy.app.PolicyRuleService;
import cn.refinex.meridian.controlplane.policy.domain.PolicyRule;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 租户与项目策略的内部接口（InternalPermissionRules 标签，ADR-0047）：只接受 runtime。 */
@RestController
public class InternalPermissionRulesApiController implements InternalPermissionRulesApi {

    private final PolicyRuleService rules;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param rules 策略规则用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalPermissionRulesApiController(PolicyRuleService rules, HttpServletRequest request) {
        this.rules = rules;
        this.request = request;
    }

    @Override
    public ResponseEntity<ProjectPermissionRules> getProjectPermissionRules(String projectId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        PolicyRuleService.Layers layers = rules.layers(projectId);
        return ResponseEntity.ok(new ProjectPermissionRules(
                layers.tenantId(),
                layers.projectId(),
                layers.tenantRules().stream()
                        .map(InternalPermissionRulesApiController::toDto)
                        .toList(),
                layers.projectRules().stream()
                        .map(InternalPermissionRulesApiController::toDto)
                        .toList()));
    }

    @Override
    public ResponseEntity<InternalPermissionRule> rememberProjectPermissionRule(
            String projectId, RememberRuleRequest body) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        return ResponseEntity.ok(toDto(rules.remember(
                projectId,
                body.getToolName(),
                body.getRuleContent(),
                body.getBehavior().getValue(),
                body.getSourceActionId(),
                body.getDecidedBy())));
    }

    private static InternalPermissionRule toDto(PolicyRule rule) {
        return new InternalPermissionRule()
                .id(rule.id())
                .toolName(rule.toolName())
                .ruleContent(rule.ruleContent())
                .behavior(InternalPermissionRule.BehaviorEnum.fromValue(rule.behavior()))
                .source(InternalPermissionRule.SourceEnum.fromValue(rule.source()));
    }
}
