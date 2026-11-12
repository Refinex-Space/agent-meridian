package cn.refinex.meridian.controlplane.policy.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.policy.domain.PolicyErrorCode;
import cn.refinex.meridian.controlplane.policy.domain.PolicyRule;
import cn.refinex.meridian.controlplane.policy.infra.PolicyRuleRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectDirectory;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.project.domain.ProjectErrorCode;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租户与项目策略的用例（架构设计「权限判定」，ADR-0047）：管理员添加、删除规则；runtime 每轮合成权限时读取两层规则；审批时"记住此决定"
 * （项目级）写入规则，相同的规则不重复写入。变更提交后经 {@code config.invalidate}（{@code permission_policy}，{@code id} 为租户或项目）
 * 通知 runtime 淘汰缓存。
 */
@Service
public class PolicyRuleService {

    /** 记住的决定没有给出主体时记的创建者：写入它的服务。 */
    static final String RUNTIME = "runtime";

    private final PolicyRuleRepository rules;
    private final ProjectService projects;
    private final ProjectDirectory directory;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param rules 规则仓储
     * @param projects 项目用例，确认项目存在
     * @param directory 按项目 ID 找租户（内部接口）
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public PolicyRuleService(
            PolicyRuleRepository rules,
            ProjectService projects,
            ProjectDirectory directory,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.rules = rules;
        this.projects = projects;
        this.directory = directory;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 管理员添加一条规则。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；为空表示租户策略
     * @param toolName 工具名
     * @param ruleContent 规则内容；可为空
     * @param behavior 结果
     * @return 新规则
     */
    public PolicyRule create(
            String actor, String tenantId, String projectId, String toolName, String ruleContent, String behavior) {
        if (projectId != null) {
            projects.require(tenantId, projectId);
        }
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    PolicyRule rule = new PolicyRule(
                            Ids.newId(IdPrefix.PERMISSION_RULE),
                            tenantId,
                            projectId,
                            toolName,
                            ruleContent,
                            behavior,
                            PolicyRule.POLICY,
                            null,
                            actor,
                            clock.instant());
                    if (!rules.insertIfAbsent(rule)) {
                        throw new ConflictException(
                                PolicyErrorCode.ALREADY_EXISTS, "已有相同的规则：" + toolName + " " + behavior);
                    }
                    audit.record(
                            "permission_rule.created",
                            actor,
                            tenantId,
                            projectId,
                            "permission_rule",
                            rule.id(),
                            details(rule));
                    changed(tenantId, projectId);
                    return rule;
                }));
    }

    /**
     * 列出一层的规则。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；为空表示租户策略
     * @param page 分页参数
     * @return 一页规则
     */
    public PageResult<PolicyRule> list(String tenantId, String projectId, PageRequest page) {
        if (projectId != null) {
            projects.require(tenantId, projectId);
        }
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> PageResult.of(rules.list(projectId, page), page, r -> Cursor.of(r.id())));
    }

    /**
     * 删除一层中的一条规则（含记住的决定）。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；为空表示租户策略
     * @param id 规则 ID
     */
    public void delete(String actor, String tenantId, String projectId, String id) {
        if (projectId != null) {
            projects.require(tenantId, projectId);
        }
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    PolicyRule rule = rules.find(projectId, id)
                            .orElseThrow(() -> new NotFoundException(PolicyErrorCode.NOT_FOUND, "权限规则不存在：" + id));
                    rules.delete(projectId, id);
                    audit.record(
                            "permission_rule.deleted",
                            actor,
                            tenantId,
                            projectId,
                            "permission_rule",
                            id,
                            details(rule));
                    changed(tenantId, projectId);
                }));
    }

    /**
     * 一个项目在权限合成中的租户层与项目层规则（内部接口）。
     *
     * @param projectId 项目 ID
     * @return 两层规则
     * @throws NotFoundException 项目不存在或已删除时（{@code project.not_found}）
     */
    public Layers layers(String projectId) {
        String tenantId = tenantOf(projectId);
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> new Layers(tenantId, projectId, rules.all(null), rules.all(projectId)));
    }

    /**
     * 记住此决定（项目级）：写入一条 {@value PolicyRule#REMEMBERED} 规则；相同的规则已存在时返回已有的。
     *
     * @param projectId 项目 ID
     * @param toolName 工具名
     * @param ruleContent 规则内容；可为空
     * @param behavior 结果：{@code allow} 或 {@code deny}
     * @param actionId 来源的待处理动作 ID
     * @param decidedBy 做出决定的主体；可为空
     * @return 写入的或已有的规则
     */
    public PolicyRule remember(
            String projectId, String toolName, String ruleContent, String behavior, String actionId, String decidedBy) {
        String tenantId = tenantOf(projectId);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    PolicyRule rule = new PolicyRule(
                            Ids.newId(IdPrefix.PERMISSION_RULE),
                            tenantId,
                            projectId,
                            toolName,
                            ruleContent,
                            behavior,
                            PolicyRule.REMEMBERED,
                            actionId,
                            decidedBy == null ? RUNTIME : decidedBy,
                            clock.instant());
                    if (!rules.insertIfAbsent(rule)) {
                        return rules.findSame(projectId, toolName, ruleContent, behavior)
                                .orElseThrow();
                    }
                    audit.recordService(
                            "permission_rule.remembered",
                            RUNTIME,
                            tenantId,
                            projectId,
                            "permission_rule",
                            rule.id(),
                            details(rule));
                    changed(tenantId, projectId);
                    return rule;
                }));
    }

    private String tenantOf(String projectId) {
        return directory
                .tenantOf(projectId)
                .orElseThrow(() -> new NotFoundException(ProjectErrorCode.NOT_FOUND, "项目不存在：" + projectId));
    }

    private void changed(String tenantId, String projectId) {
        invalidations.resourceChanged(
                ConfigInvalidations.PERMISSION_POLICY, tenantId, projectId == null ? tenantId : projectId);
    }

    private static Map<String, Object> details(PolicyRule rule) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool_name", rule.toolName());
        details.put("rule_content", rule.ruleContent());
        details.put("behavior", rule.behavior());
        details.put("source", rule.source());
        details.put("source_action_id", rule.sourceActionId());
        return details;
    }

    /**
     * 两层规则。
     *
     * @param tenantId 项目所属租户
     * @param projectId 项目 ID
     * @param tenantRules 租户策略，按创建顺序
     * @param projectRules 项目策略（含记住的决定），按创建顺序
     */
    public record Layers(
            String tenantId, String projectId, List<PolicyRule> tenantRules, List<PolicyRule> projectRules) {}
}
