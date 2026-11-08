package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.util.Optional;

/**
 * "记住此决定"产生的规则（架构设计「待处理动作」「权限判定」）：对被确认的工具记一条整工具规则，批准记为 allow、拒绝记为 deny，
 * {@code remember_scope=session} 写入会话层，{@code project} 写入项目层。
 *
 * <p>AgentScope 只在批准时把 {@code ConfirmResult.rules} 加进本轮的权限引擎，且不写回状态，所以平台保存规则，之后每轮合成时注入。
 * 规则不带内容：工具的内容级建议（命令前缀、路径）在平台工具实现 {@code generateSuggestions} 之后由内核随动作带出（登记事项 D21）。
 * 纯函数，线程安全。
 */
public final class RememberedRules {

    private RememberedRules() {}

    /**
     * 一个确认类结论要记住的规则。
     *
     * @param toolName 被确认的工具名
     * @param conclusion 结论
     * @return 规则；不记住或不是确认类结论时为空
     */
    public static Optional<PermissionRule> of(String toolName, ActionConclusion conclusion) {
        if (conclusion.approved() == null
                || conclusion.remember() == null
                || conclusion.remember() == RememberScope.NONE) {
            return Optional.empty();
        }
        PolicyLayer layer = conclusion.remember() == RememberScope.SESSION ? PolicyLayer.SESSION : PolicyLayer.PROJECT;
        PermissionBehavior behavior = conclusion.isApproved() ? PermissionBehavior.ALLOW : PermissionBehavior.DENY;
        return Optional.of(new PermissionRule(toolName, null, behavior, layer));
    }
}
