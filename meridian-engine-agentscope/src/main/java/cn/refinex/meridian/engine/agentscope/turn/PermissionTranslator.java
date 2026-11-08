package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;

/**
 * 把平台的权限策略翻译为 AgentScope 的权限类型，见架构设计「权限判定」。
 *
 * <p>只做无损翻译：五层规则的合成、unattended 与子线程的改写都在 runtime 完成（T08），这里收到的已是合成结果。
 * 规则的 {@code source} 取平台规则所属的层（{@code platform}、{@code tenant}…），便于在审计与排查时追溯。
 */
final class PermissionTranslator {

    private PermissionTranslator() {}

    /**
     * 翻译合成后的策略，用于每轮开始前经 {@code replacePermissionContext} 安装。
     *
     * @param policy 合成后的权限策略
     * @return AgentScope 的权限上下文
     */
    static PermissionContextState toContextState(PermissionPolicy policy) {
        return toContextState(policy, List.of());
    }

    /**
     * 翻译合成后的策略，并入智能体实例的策略拒绝规则（{@code ToolPolicy.denyRules}，ADR-0034）。拒绝规则在权限引擎中最先判定，
     * 并入的规则不会被合成结果中的 allow 放开。平凡的策略不并入（见方法体）。
     *
     * @param policy 合成后的权限策略
     * @param policyDenyRules 智能体实例的策略拒绝规则
     * @return AgentScope 的权限上下文
     */
    static PermissionContextState toContextState(
            PermissionPolicy policy, List<cn.refinex.meridian.engine.spi.spec.PermissionRule> policyDenyRules) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.valueOf(policy.mode().name()));
        List<cn.refinex.meridian.engine.spi.spec.PermissionRule> rules = new java.util.ArrayList<>(policy.rules());
        // 平凡的策略（DEFAULT、没有规则）在 2.0.4 中一律放行；并入规则会把它变成"没有命中 allow 都要确认"，改变语义。
        // runtime 合成的策略总带平台安全规则（ADR-0031），从不平凡，所以生产中拒绝规则总会并入；平凡策略只出现在内核测试中
        if (!isTrivial(policy)) {
            rules.addAll(policyDenyRules);
        }
        for (cn.refinex.meridian.engine.spi.spec.PermissionRule rule : rules) {
            PermissionRule translated = toRule(rule);
            switch (rule.behavior()) {
                case ALLOW -> builder.addAllowRule(rule.toolName(), translated);
                case DENY -> builder.addDenyRule(rule.toolName(), translated);
                case ASK -> builder.addAskRule(rule.toolName(), translated);
            }
        }
        return builder.build();
    }

    private static boolean isTrivial(PermissionPolicy policy) {
        return policy.mode() == cn.refinex.meridian.engine.spi.spec.PermissionMode.DEFAULT
                && policy.rules().isEmpty();
    }

    /**
     * 翻译一组规则，用于审批时"记住此决定"的 {@code ConfirmResult.rules}。
     *
     * @param rules 平台规则
     * @return AgentScope 规则；没有规则时为空，与 AgentScope 的"没有附带规则"一致
     */
    static List<PermissionRule> toRules(List<cn.refinex.meridian.engine.spi.spec.PermissionRule> rules) {
        return rules.isEmpty()
                ? null
                : rules.stream().map(PermissionTranslator::toRule).toList();
    }

    private static PermissionRule toRule(cn.refinex.meridian.engine.spi.spec.PermissionRule rule) {
        return new PermissionRule(
                rule.toolName(),
                rule.ruleContent(),
                PermissionBehavior.valueOf(rule.behavior().name()),
                rule.layer().value());
    }
}
