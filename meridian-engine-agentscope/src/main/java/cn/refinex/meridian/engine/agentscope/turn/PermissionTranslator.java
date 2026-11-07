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
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.valueOf(policy.mode().name()));
        for (cn.refinex.meridian.engine.spi.spec.PermissionRule rule : policy.rules()) {
            PermissionRule translated = toRule(rule);
            switch (rule.behavior()) {
                case ALLOW -> builder.addAllowRule(rule.toolName(), translated);
                case DENY -> builder.addDenyRule(rule.toolName(), translated);
                case ASK -> builder.addAskRule(rule.toolName(), translated);
            }
        }
        return builder.build();
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
