package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link PolicyStorePort} 的开发实现：规则存放在 {@code rt.permission_rules}（T15b 之前的过渡，见端口说明）。租户层规则没有写入接口，
 * 运维或测试直接写表；项目层规则由"记住此决定"写入。写入在调用方的事务中执行。
 *
 * <p>线程安全：无状态。
 */
@Component
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
