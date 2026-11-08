package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.allowAll;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.PlatformContextMessage;
import cn.refinex.meridian.engine.agentscope.assembly.PlatformPrompt;
import cn.refinex.meridian.engine.agentscope.testkit.model.RecordedRequest;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 系统提示词的四层顺序与请求前缀稳定性（T10b-A06，ADR-0035）：平台基础提示词 → 租户 → 智能体 → 技能清单；时间以
 * {@code <platform_context>} 消息注入；同一智能体的两个会话在第一条用户消息之前的请求内容相同。
 */
class PromptLayeringScenarioTest {

    private static final String TENANT_PROMPT = "按集团的报销制度回答，金额一律用人民币。";

    @Test
    void systemPromptIsLayeredAndTheTimeArrivesAsAPlatformContextMessage() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.text("好的"))
                .respond(r -> r.text("好的"))
                .build();
        EngineHarness harness = harness(model);

        harness.run(harness.claim("thr_layers_a", "trn_1", 1, allowAll(), "u_1", List.of()), userText("报销流程？"));
        harness.run(harness.claim("thr_layers_b", "trn_1", 1, allowAll(), "u_2", List.of()), userText("差旅标准？"));

        RecordedRequest first = model.request(0);
        RecordedRequest second = model.request(1);
        String prompt = first.systemPrompt();
        int base = prompt.indexOf(PlatformPrompt.BASE.strip());
        int tenant = prompt.indexOf(TENANT_PROMPT);
        int agent = prompt.indexOf(harness.spec.systemPrompt());
        int skills = prompt.indexOf("report-writer");
        assertThat(base).as("平台基础提示词在最前").isZero();
        assertThat(tenant).as("租户层在平台基础提示词之后").isGreaterThan(base);
        assertThat(agent).as("智能体层在租户层之后").isGreaterThan(tenant);
        assertThat(skills).as("技能清单在最后").isGreaterThan(agent);
        assertThat(prompt).as("动态内容不进系统提示词").doesNotContain("2026-11-08").doesNotContain("Today's date");
        assertThat(prompt)
                .as("AGENTS.md 不再经工作区上下文重复注入（D29）")
                .doesNotContain("<loaded_context>")
                .containsOnlyOnce(harness.spec.systemPrompt());

        assertThat(prefix(second)).as("同一智能体的两个会话：第一条用户消息之前的消息逐字相同").isEqualTo(prefix(first));
        assertThat(tools(second)).as("工具定义相同且顺序稳定").isEqualTo(tools(first));

        Msg context = first.messages().get(1);
        assertThat(context.getRole()).isEqualTo(MsgRole.USER);
        assertThat(context.getMetadata()).containsKey(PlatformContextMessage.METADATA_KEY);
        assertThat(((TextBlock) context.getContent().getFirst()).getText())
                .isEqualTo("<platform_context>\nCurrent time: 2026-11-08T08:30:00Z (UTC)\n</platform_context>");
        assertThat(first.lastUserText()).isEqualTo("报销流程？");
    }

    private static EngineHarness harness(ScriptedModel model) {
        EngineHarness harness = EngineHarness.withPlatform(
                model,
                spec -> spec.tools(PlatformToolNames.READ_FILE).skill(new SkillRef("skl_report", 2, "report-writer")),
                List.of(SkillScenarioTest.REPORT_WRITER));
        harness.configs.tenantPrompt(EngineHarness.TENANT, TENANT_PROMPT);
        return harness;
    }

    /** 工具定义：名字、描述、入参 schema，按请求中的顺序。 */
    private static List<String> tools(RecordedRequest request) {
        return request.tools().stream()
                .map(t -> t.getName() + "|" + t.getDescription() + "|" + t.getParameters())
                .toList();
    }

    /** 第一条用户消息之前的消息（系统提示词），按角色与正文比较。 */
    private static List<String> prefix(RecordedRequest request) {
        return request.messages().stream()
                .takeWhile(m -> m.getRole() != MsgRole.USER)
                .map(m -> m.getRole() + ":" + m.getTextContent())
                .toList();
    }
}
