package cn.refinex.meridian.engine.agentscope.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.model.TokenEstimator;
import cn.refinex.meridian.engine.spi.event.ContextComposition;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.skill.runtime.SkillPromptBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 上下文构成摘要的分类与统计（架构设计「上下文管理」，T09a-A07）。 */
class ContextCompositionMiddlewareTest {

    private static final String BASE_PROMPT = "你是企业内的通用助手。";
    private static final String SKILLS = SkillPromptBuilder.DEFAULT_HEADER
            + "<skill>\n  <name>报表</name>\n  <skill-id>report</skill-id>\n</skill>\n\n"
            + "<skill>\n  <name>翻译</name>\n  <skill-id>translate</skill-id>\n</skill>\n\n"
            + "</available_skills>";

    @Test
    void messagesAreCountedBySourceWithoutKeepingAnyContent() {
        Msg system = text(MsgRole.SYSTEM, BASE_PROMPT + "\n\n" + SKILLS);
        Msg user = text(MsgRole.USER, "帮我查订单");
        Msg assistant = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call_1", "lookup_order", Map.of("id", "o1")))
                .build();
        Msg toolResult = Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.builder()
                        .id("call_1")
                        .name("lookup_order")
                        .output(List.of(TextBlock.builder().text("订单已发货").build()))
                        .build())
                .build();
        Msg steer = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("改查另一个").build())
                .metadata(Map.of(SteerMiddleware.METADATA_KEY, "evt_1"))
                .build();
        Msg recovery = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("恢复说明").build())
                .metadata(Map.of(RecoveryNoteMiddleware.METADATA_KEY, true))
                .build();

        ContextComposition composition =
                ContextCompositionMiddleware.compose(List.of(system, user, assistant, toolResult, steer, recovery));

        int skillTokens = TokenEstimator.text(SKILLS);
        assertThat(composition.skills()).as("两个技能").isEqualTo(new ContextComposition.Part(2, skillTokens));
        assertThat(composition.systemPrompt())
                .isEqualTo(new ContextComposition.Part(1, TokenEstimator.message(system) - skillTokens));
        assertThat(composition.history())
                .isEqualTo(new ContextComposition.Part(
                        2, TokenEstimator.message(user) + TokenEstimator.message(assistant)));
        assertThat(composition.toolResults())
                .isEqualTo(new ContextComposition.Part(1, TokenEstimator.message(toolResult)));
        assertThat(composition.injected())
                .as("steer 与恢复说明都是平台注入的内容")
                .isEqualTo(new ContextComposition.Part(
                        2, TokenEstimator.message(steer) + TokenEstimator.message(recovery)));
        assertThat(composition.toString()).as("摘要只有数字，不含正文").doesNotContain("订单", "助手", "报表");
    }

    @Test
    void systemPromptWithoutSkillsHasAnEmptySkillPart() {
        ContextComposition composition =
                ContextCompositionMiddleware.compose(List.of(text(MsgRole.SYSTEM, BASE_PROMPT)));

        assertThat(composition.skills()).isEqualTo(ContextComposition.Part.EMPTY);
        assertThat(composition.systemPrompt().count()).isEqualTo(1);
    }

    private static Msg text(MsgRole role, String text) {
        return Msg.builder()
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
