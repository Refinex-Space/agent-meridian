package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import java.time.Clock;
import java.util.List;

/**
 * {@link PolicyStorePort} 的开发实现：规则存放在 {@code rt.permission_rules}，只在 {@code agent-config.source=file}（dev、test profile）
 * 时由 {@code config.AgentConfigConfiguration} 装配；生产实现是 control-plane 下发的 {@code ControlPlanePolicyStore}（ADR-0047）。租户层规则没有写入接口，
 * 运维或测试直接写表；项目层规则由"记住此决定"写入。写入在调用方的事务中执行。
 *
 * <p>线程安全：无状态。
 */
public class DatabasePolicyStore implements PolicyStorePort {

    private final PermissionRuleRepository rules;
    private final Clock clock;

    /**
     * 创建开发实现。
     *
     * @param rules 权限规则仓储
     * @param clock 时钟
     */
    public DatabasePolicyStore(PermissionRuleRepository rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    @Override
    public List<PermissionRule> tenantRules() {
        return rules.listTenantAndProject().stream()
                .filter(rule -> rule.layer() == PolicyLayer.TENANT)
                .toList();
    }

    @Override
    public List<PermissionRule> projectRules() {
        return rules.listTenantAndProject().stream()
                .filter(rule -> rule.layer() == PolicyLayer.PROJECT)
                .toList();
    }

    @Override
    public void rememberProjectRule(PermissionRule rule, String actionId, String decidedBy) {
        rules.insert(rule, null, actionId, decidedBy, clock.instant());
    }
}
