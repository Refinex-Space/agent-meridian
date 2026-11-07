package cn.refinex.meridian.engine.agentscope.turn;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 输入与权限的翻译：四种输入各自翻译为互不混合的消息，审批结果是 {@link ConfirmResult} 实例；
 * 待确认调用存在时 AgentScope 丢弃同批普通消息（ADR-0016），本翻译不会产生这种组合。
 */
class TurnInputTranslatorTest {

    @Test
    void newMessagesBecomeUserMessagesWithoutConfirmationsOrToolResults() {
        List<Msg> messages = TurnInputTranslator.messages(new TurnInput.NewMessages(
                List.of(
                        new InboundMessage(
                                "evt_1",
                                List.of(
                                        new MessageContent.Text("看图"),
                                        new MessageContent.Image("https://e.com/a.png", null, "image/png"))),
                        new InboundMessage(
                                "evt_2", List.of(new MessageContent.FileRef("fil_1", "报告.pdf", "application/pdf")))),
                null));

        assertThat(messages).hasSize(2).allSatisfy(m -> {
            assertThat(m.getRole()).isEqualTo(MsgRole.USER);
            assertThat(m.getMetadata() == null ? Map.of() : m.getMetadata())
                    .doesNotContainKey(Msg.METADATA_CONFIRM_RESULTS);
            assertThat(m.hasContentBlocks(ToolResultBlock.class)).isFalse();
        });
        assertThat(messages.get(0).getContent()).hasSize(2);
        assertThat(messages.get(0).getContent().get(1))
                .isInstanceOfSatisfying(
                        ImageBlock.class,
                        image -> assertThat(((URLSource) image.getSource()).getUrl())
                                .isEqualTo("https://e.com/a.png"));
        assertThat(((TextBlock) messages.get(1).getContent().getFirst()).getText())
                .isEqualTo("[文件 fil_1 报告.pdf (application/pdf)]");
    }

    @Test
    void confirmationsAreConfirmResultInstancesInAMessageWithoutText() {
        PermissionRule remembered = new PermissionRule("echo", null, PermissionBehavior.ALLOW, PolicyLayer.SESSION);
        TurnInput.Confirmations input = new TurnInput.Confirmations(
                List.of(
                        new ToolConfirmation("call_1", "echo", Map.of("text", "a"), true, null, List.of(remembered)),
                        new ToolConfirmation("call_2", "echo", Map.of("text", "b"), false, "不允许", List.of())),
                null);

        List<Msg> messages = TurnInputTranslator.messages(input);

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.getContent()).as("确认轮不带普通文本").isEmpty();
            assertThat(m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .hasSize(2)
                    .allSatisfy(r -> assertThat(r).isInstanceOf(ConfirmResult.class));
        });
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) messages.getFirst().getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getToolCall().getInput()).containsEntry("text", "a");
        assertThat(results.get(0).getToolCall().getContent())
                .as("2.0.4 按入参原文校验参数")
                .isEqualTo("{\"text\":\"a\"}");
        assertThat(results.get(0).getRules()).singleElement().satisfies(r -> {
            assertThat(r.toolName()).isEqualTo("echo");
            assertThat(r.source()).isEqualTo("session");
        });
        assertThat(results.get(1).isConfirmed()).isFalse();
        assertThat(results.get(1).getReason()).isEqualTo("不允许");
        assertThat(results.get(1).getRules()).isNull();
        assertThat(TurnInputTranslator.approvedToolUseIds(input)).containsExactly("call_1");
    }

    @Test
    void customToolResultsBecomeToolResultBlocksOnly() {
        List<Msg> messages = TurnInputTranslator.messages(new TurnInput.CustomToolResults(
                List.of(
                        new CustomToolResult("call_c", "lookup_order", List.of(new MessageContent.Text("已发货")), false),
                        new CustomToolResult(
                                "call_d", "lookup_order", List.of(new MessageContent.Text("timeout")), true)),
                null));

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.getRole()).isEqualTo(MsgRole.TOOL);
            assertThat(m.getContent()).allSatisfy(b -> assertThat(b).isInstanceOf(ToolResultBlock.class));
        });
        List<ToolResultBlock> blocks = messages.getFirst().getContentBlocks(ToolResultBlock.class);
        assertThat(blocks).extracting(ToolResultBlock::getId).containsExactly("call_c", "call_d");
        assertThat(blocks)
                .extracting(ToolResultBlock::getState)
                .containsExactly(ToolResultState.SUCCESS, ToolResultState.ERROR);
    }

    @Test
    void recoveryOnlyHasNoInput() {
        assertThat(TurnInputTranslator.messages(new TurnInput.RecoveryOnly(new RecoveryNote(List.of()))))
                .isEmpty();
    }

    @Test
    void customToolsAreInjectedAsSchemaOnlyToolsWithExternalPriority() {
        assertThat(TurnInputTranslator.toolRequestConfig(List.of())).isNull();

        ToolRequestConfig config = TurnInputTranslator.toolRequestConfig(
                List.of(new CustomToolDeclaration("lookup_order", "查询订单", Map.of("type", "object"), SideEffect.NONE)));

        assertThat(config.mergeMode()).isEqualTo(ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
        assertThat(config.externalTools()).containsOnlyKeys("lookup_order");
        assertThat(config.externalTools().get("lookup_order").getDescription()).isEqualTo("查询订单");
    }

    @Test
    void permissionPoliciesAreTranslatedLosslessly() {
        PermissionContextState state = PermissionTranslator.toContextState(new PermissionPolicy(
                PermissionMode.DONT_ASK,
                List.of(
                        new PermissionRule("read_file", "reports/**", PermissionBehavior.ALLOW, PolicyLayer.PROJECT),
                        new PermissionRule("bash", "rm:*", PermissionBehavior.DENY, PolicyLayer.PLATFORM),
                        new PermissionRule("bash", null, PermissionBehavior.ASK, PolicyLayer.TENANT))));

        assertThat(state.getMode()).isEqualTo(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        assertThat(state.getAllowRules().get("read_file")).singleElement().satisfies(r -> {
            assertThat(r.ruleContent()).isEqualTo("reports/**");
            assertThat(r.source()).isEqualTo("project");
        });
        assertThat(state.getDenyRules().get("bash"))
                .singleElement()
                .satisfies(r -> assertThat(r.ruleContent()).isEqualTo("rm:*"));
        assertThat(state.getAskRules().get("bash"))
                .singleElement()
                .satisfies(r -> assertThat(r.ruleContent()).isNull());
    }
}
