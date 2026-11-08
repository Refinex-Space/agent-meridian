package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import java.util.List;

/**
 * runtime 内部端口：租户策略与项目策略的规则，以及"记住此决定"写入的项目级规则（架构设计「权限判定」「待处理动作」）。
 *
 * <p>T15b 起租户与项目策略由 control-plane 管理并下发，项目级"记住此决定"写入 control-plane；在那之前由 {@code rt.permission_rules}
 * 的开发实现提供（{@code approval.infra.DatabasePolicyStore}）。租户与项目取自当前线程绑定的 {@code PlatformContext}。
 *
 * <p>方法会阻塞，在虚拟线程上调用。
 */
public interface PolicyStorePort {

    /**
     * 当前租户的租户策略规则。
     *
     * @return 规则，层为 {@code TENANT}；没有时为空列表
     */
    List<PermissionRule> tenantRules();

    /**
     * 当前项目的项目策略规则，含"记住此决定"写入的项目级规则。
     *
     * @return 规则，层为 {@code PROJECT}；没有时为空列表
     */
    List<PermissionRule> projectRules();

    /**
     * "记住此决定"（{@code remember_scope=project}）写入一条项目级规则，之后每轮合成时生效。相同的规则不重复写入。
     *
     * @param rule 规则，层为 {@code PROJECT}
     * @param actionId 来源的待处理动作 ID
     * @param decidedBy 做出决定的主体；可为空
     */
    void rememberProjectRule(PermissionRule rule, String actionId, String decidedBy);
}
