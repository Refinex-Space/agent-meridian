package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.approval.domain.PermissionLayers;
import cn.refinex.meridian.runtime.approval.domain.PermissionSynthesizer;
import cn.refinex.meridian.runtime.approval.infra.PermissionRuleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 每轮开始前读取五层规则并合成权限（架构设计「权限判定」）：平台安全规则、租户与项目策略（{@link PolicyStorePort}）、智能体版本的
 * 策略、会话级规则（{@code rt.permission_rules}），合成逻辑在 {@link PermissionSynthesizer}。
 *
 * <p>方法会阻塞，调用线程上要绑定轮次所在项目的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class PermissionPolicies {

    private final PolicyStorePort store;
    private final PermissionRuleRepository rules;

    /**
     * 创建用例。
     *
     * @param store 租户与项目策略
     * @param rules 权限规则仓储，读取会话级规则
     */
    public PermissionPolicies(PolicyStorePort store, PermissionRuleRepository rules) {
        this.store = store;
        this.rules = rules;
    }

    /**
     * 合成一轮的权限：主线程的策略，以及为智能体配置的每个子智能体预先合成的策略。
     *
     * @param sessionId 会话 ID
     * @param interaction 会话的值守方式
     * @param spec 会话绑定的智能体版本配置
     * @param customTools 会话声明的自定义工具，在会话层放行
     * @return 合成结果
     */
    public TurnPermissions forTurn(
            String sessionId,
            Interaction interaction,
            ResolvedAgentSpec spec,
            List<CustomToolDeclaration> customTools) {
        List<PermissionRule> sessionRules = new ArrayList<>(rules.listSession(sessionId));
        sessionRules.addAll(PermissionSynthesizer.customToolRules(
                customTools.stream().map(CustomToolDeclaration::name).toList()));
        PermissionLayers layers = new PermissionLayers(
                store.tenantRules(), store.projectRules(), spec.permissions(), sessionRules, interaction);
        return new TurnPermissions(
                PermissionSynthesizer.mainThread(layers), PermissionSynthesizer.subagents(layers, spec.subagents()));
    }

    /**
     * 一轮的权限合成结果。
     *
     * @param main 主线程的策略
     * @param subagents 子智能体名 → 子线程的策略
     */
    public record TurnPermissions(PermissionPolicy main, Map<String, PermissionPolicy> subagents) {

        /**
         * 创建合成结果。
         *
         * @throws NullPointerException 缺少主线程策略时
         */
        public TurnPermissions {
            Objects.requireNonNull(main, "main");
            subagents = subagents == null ? Map.of() : Map.copyOf(subagents);
        }
    }
}
