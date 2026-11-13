package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.util.Map;
import java.util.Optional;

/**
 * "记住此决定"产生的规则（架构设计「待处理动作」「权限判定」）：批准记为 allow、拒绝记为 deny，{@code remember_scope=session} 写入
 * 会话层，{@code project} 写入项目层。
 *
 * <p>一般工具记整工具规则。{@code bash} 记这一条命令（规则内容为命令原文，不带 {@code :*} 即整条命令相同才匹配）：记整工具规则会
 * 放行之后的任何命令（T12a，ADR-0037）。复合命令与看不出会执行什么的命令按原文记下也不会匹配（规则逐段比较），等于不放行。回应时
 * 选了动作的一条建议规则（命令前缀、路径等，内核随动作带出）时记这一条（登记事项 D21，ADR-0053）。
 *
 * <p>AgentScope 只在批准时把 {@code ConfirmResult.rules} 加进本轮的权限引擎，且不写回状态，所以平台保存规则，之后每轮合成时注入。
 * 纯函数，线程安全。
 */
public final class RememberedRules {

    private RememberedRules() {}

    /**
     * 一个确认类结论要记住的规则。
     *
     * @param toolName 被确认的工具名
     * @param input 被确认的入参（审批人修改过时为修改后的入参）
     * @param conclusion 结论
     * @return 规则；不记住、不是确认类结论或 {@code bash} 没有命令时为空
     */
    public static Optional<PermissionRule> of(String toolName, Map<String, Object> input, ActionConclusion conclusion) {
        if (conclusion.approved() == null
                || conclusion.remember() == null
                || conclusion.remember() == RememberScope.NONE) {
            return Optional.empty();
        }
        String content = null;
        if (conclusion.rememberRule() != null) {
            content = conclusion.rememberRule();
        } else if (PlatformToolNames.BASH.equals(toolName)) {
            if (!(input.get("command") instanceof String command) || command.isBlank()) {
                return Optional.empty();
            }
            content = command.strip();
        }
        PolicyLayer layer = conclusion.remember() == RememberScope.SESSION ? PolicyLayer.SESSION : PolicyLayer.PROJECT;
        PermissionBehavior behavior = conclusion.isApproved() ? PermissionBehavior.ALLOW : PermissionBehavior.DENY;
        return Optional.of(new PermissionRule(toolName, content, behavior, layer));
    }
}
