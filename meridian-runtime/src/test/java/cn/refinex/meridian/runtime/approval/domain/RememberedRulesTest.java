package cn.refinex.meridian.runtime.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link RememberedRules}："记住此决定"的规则，{@code bash} 记这一条命令而不是整个工具（T12a）。 */
class RememberedRulesTest {

    static ActionConclusion confirm(boolean approved, RememberScope scope) {
        return ActionConclusion.of(new UserEvent.ToolConfirmation("act_1", approved, null, null, scope));
    }

    @Test
    void ordinaryToolsAreRememberedAsWholeToolRules() {
        assertThat(RememberedRules.of("write_file", Map.of("path", "a.md"), confirm(true, RememberScope.SESSION)))
                .contains(new PermissionRule("write_file", null, PermissionBehavior.ALLOW, PolicyLayer.SESSION));
        assertThat(RememberedRules.of("write_file", Map.of(), confirm(false, RememberScope.PROJECT)))
                .contains(new PermissionRule("write_file", null, PermissionBehavior.DENY, PolicyLayer.PROJECT));
        assertThat(RememberedRules.of("write_file", Map.of(), confirm(true, RememberScope.NONE)))
                .isEmpty();
    }

    @Test
    void bashIsRememberedAsTheExactCommandNotTheWholeTool() {
        assertThat(RememberedRules.of(
                        "bash", Map.of("command", "  git status --short "), confirm(true, RememberScope.SESSION)))
                .contains(new PermissionRule(
                        "bash", "git status --short", PermissionBehavior.ALLOW, PolicyLayer.SESSION));
        assertThat(RememberedRules.of("bash", Map.of("command", "rm -rf build"), confirm(false, RememberScope.PROJECT)))
                .contains(new PermissionRule("bash", "rm -rf build", PermissionBehavior.DENY, PolicyLayer.PROJECT));
        assertThat(RememberedRules.of("bash", Map.of(), confirm(true, RememberScope.SESSION)))
                .as("没有命令时不记，不能退回整工具规则")
                .isEmpty();
    }
}
