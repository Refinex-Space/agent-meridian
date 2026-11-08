package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.allowAll;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.result;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.text;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.ToolResultState;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 技能（T10a-A05）：智能体版本引用的技能经 {@code AgentConfigPort.skill} 解析、放进受保护路径 {@code skills/}，
 * {@code MeridianSkillRepository} 在其上读取——技能清单出现在系统提示词中，{@code load_skill_through_path} 加载正文与资源，
 * 技能文件对智能体只读，且不在本机磁盘暂存。
 */
class SkillScenarioTest {

    static final SkillDefinition REPORT_WRITER = new SkillDefinition(
            "skl_report",
            2,
            "report-writer",
            Map.of("SKILL.md", """
                    ---
                    name: report-writer
                    description: 按公司模板撰写周报与月报
                    ---
                    # 撰写报告

                    先读取 templates/outline.md 中的提纲，再逐节填写。
                    """, "templates/outline.md", "1. 本周进展\n2. 风险\n3. 下周计划\n"));

    @Test
    void theSkillListIsInTheSystemPromptAndTheSkillBodyLoadsThroughThePath() {
        String skillId = "report-writer_meridian";
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_body", "load_skill_through_path", Map.of("skillId", skillId, "path", "SKILL.md")))
                .respond(r -> r.toolCall(
                        "call_res",
                        "load_skill_through_path",
                        Map.of("skillId", skillId, "path", "templates/outline.md")))
                .respond(r -> r.toolCall("call_read", "read_file", Map.of("path", "skills/report-writer/SKILL.md")))
                .respond(r -> r.toolCall(
                        "call_write",
                        "edit_file",
                        Map.of("path", "skills/report-writer/SKILL.md", "old_string", "撰写报告", "new_string", "随便写")))
                .respond(r -> r.text("周报已写好"))
                .build();
        EngineHarness harness = EngineHarness.withPlatform(
                model,
                spec -> spec.tools(PlatformToolNames.READ_FILE, PlatformToolNames.EDIT_FILE)
                        .skill(new SkillRef("skl_report", 2, "report-writer")),
                List.of(REPORT_WRITER));

        List<EngineEvent> events = harness.run(harness.claim("thr_skill", "trn_1", 1, allowAll()), userText("写周报"));

        String systemPrompt = model.request(0).systemPrompt();
        assertThat(systemPrompt).as("技能清单出现在系统提示词中").contains("report-writer").contains("按公司模板撰写周报与月报");
        assertThat(text(result(events, "call_body")))
                .as("load_skill_through_path 加载正文")
                .contains("先读取 templates/outline.md 中的提纲");
        assertThat(text(result(events, "call_res"))).contains("1. 本周进展");
        assertThat(text(result(events, "call_read"))).as("技能目录在工作区中可读").contains("name: report-writer");
        assertThat(result(events, "call_write").state()).as("技能目录只读").isEqualTo(ToolResultState.ERROR);
        assertThat(Files.exists(InMemoryWorkspaceFilesystems.LOCAL_WORKSPACE_ROOT))
                .as("技能资源不暂存到本机工作区（MarketplaceStager 把它视为工作区原生）")
                .isFalse();
    }
}
