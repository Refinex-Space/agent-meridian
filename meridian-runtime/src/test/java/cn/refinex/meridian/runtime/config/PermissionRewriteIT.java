package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static cn.refinex.meridian.runtime.config.WorkerFixture.types;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.PathRuleTool;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import cn.refinex.meridian.runtime.approval.domain.PermissionLayers;
import cn.refinex.meridian.runtime.approval.domain.PermissionSynthesizer;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T08-A06（P15）与 P6 审批部分：真实的 AgentScope 2.0.4 权限引擎判定平台合成的权限（架构设计「权限判定」）。
 *
 * <ul>
 *   <li>显式 ask 规则在 {@code DONT_ASK} 下仍返回 ask，所以 unattended 会话与子线程靠"ask 改写为 deny"才不会停在等待审批。
 *   <li>unattended 会话不能进入计划模式（{@code plan_enter} 被 deny）。
 *   <li>工具自检返回的安全类 ask 不受规则改写影响，unattended 会话由平台自动拒绝并立即运行恢复轮，不产生待处理动作。
 *   <li>内容级规则经工具的 {@code matchRule} 生效；平台安全规则（受保护路径、凭据文件）优先于智能体的放行。
 * </ul>
 */
class PermissionRewriteIT {

    private static final String EDIT = "edit_doc";

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    /** 智能体：放行查询，对 {@code edit_doc(reports/**)} 显式 ask。 */
    private static PermissionPolicy askForReports() {
        return new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(
                        new PermissionRule(LOOKUP, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION),
                        new PermissionRule(EDIT, "reports/**", PermissionBehavior.ASK, PolicyLayer.AGENT_VERSION),
                        new PermissionRule(EDIT, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));
    }

    @Test
    void attendedSessionWaitsForApprovalOnAnExplicitAskRule() {
        PathRuleTool edit = PathRuleTool.writing(EDIT);
        WorkerFixture fixture = fixture(
                edit,
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_e", EDIT, Map.of("path", "reports/q3.md")))
                        .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("改季度报告", Delivery.QUEUE));

        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).outcome())
                .isEqualTo(TurnOutcome.AWAITING_ACTION);
        assertThat(fixture.actions(session))
                .singleElement()
                .satisfies(a -> assertThat(a.toolName()).isEqualTo(EDIT));
        assertThat(edit.calls()).isEmpty();
    }

    @Test
    void unattendedSessionDoesNotStopOnAnExplicitAskRule() {
        PathRuleTool edit = PathRuleTool.writing(EDIT);
        WorkerFixture fixture = fixture(
                edit,
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_e", EDIT, Map.of("path", "reports/q3.md"))
                                .toolCall("call_d", EDIT, Map.of("path", "drafts/notes.md")))
                        .respond(r -> r.text("季度报告需要人工确认，已跳过"))
                        .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.UNATTENDED, List.of());
        fixture.post(node, session, message("改季度报告和草稿", Delivery.QUEUE));

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.actions(session)).as("不产生待处理动作").isEmpty();
        assertThat(edit.calledPaths()).as("ask 改写为 deny；同批中被放行的调用照常执行").containsExactly("drafts/notes.md");
        assertThat(fixture.model.request(1).toolResults())
                .filteredOn(block -> block.getId().equals("call_e"))
                .singleElement()
                .satisfies(block -> assertThat(block.getState()).isEqualTo(ToolResultState.DENIED));
        assertThat(types(fixture.events(node, session))).doesNotContain("session.status_requires_action");
    }

    @Test
    void unattendedSessionCannotEnterPlanMode() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_p", PermissionSynthesizer.PLAN_ENTER, Map.of()))
                        .respond(r -> r.text("无法进入计划模式，直接执行"))
                        .build(),
                agent -> agent.planMode(true));
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.UNATTENDED, List.of());
        fixture.post(node, session, message("先做个计划", Delivery.QUEUE));

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getName()).isEqualTo(PermissionSynthesizer.PLAN_ENTER);
            assertThat(block.getState()).isEqualTo(ToolResultState.DENIED);
        });
        assertThat(fixture.actions(session)).isEmpty();
    }

    @Test
    void unattendedSessionAutoDeniesASafetyAskAndResumesWithoutPendingActions() {
        PathRuleTool edit = PathRuleTool.withSafetyAsk(EDIT, Set.of("secrets.txt"));
        WorkerFixture fixture = fixture(
                edit,
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_s", EDIT, Map.of("path", "secrets.txt")))
                        .respond(r -> r.text("敏感文件不能修改"))
                        .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.UNATTENDED, List.of());
        fixture.post(node, session, message("改 secrets.txt", Delivery.QUEUE));

        TurnResult first = node.claimAndRun();

        // 安全类 ask 不受规则改写影响：内核以 PERMISSION_ASKING 结束，平台自动拒绝，本轮以 tools_denied 结束
        assertThat(first)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.TOOLS_DENIED);
        assertThat(fixture.actions(session)).isEmpty();
        assertThat(WorkerFixture.latestTurn(session))
                .containsEntry("kind", "confirmations")
                .containsEntry("status", "queued");

        TurnResult resumed = node.claimAndRun();

        assertThat(resumed)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(edit.calls()).isEmpty();
        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getState()).isEqualTo(ToolResultState.DENIED);
            assertThat(block.getOutput().toString()).contains("无人值守");
        });
        assertThat(types(fixture.events(node, session))).doesNotContain("session.status_requires_action");
        assertThat(DbAssertions.rows(
                        "SELECT payload::text AS payload FROM rt.outbox WHERE topic = 'audit' "
                                + "AND payload ->> 'session_id' = ? AND payload ->> 'action' = 'permission.decided'",
                        session.sessionId()))
                .anySatisfy(row -> assertThat((String) row.get("payload"))
                        .contains("\"decision\": \"denied\"", "\"source\": \"unattended\""));
    }

    @Test
    void subagentThreadDoesNotStopOnAnExplicitAskRule() {
        PathRuleTool edit = PathRuleTool.writing(EDIT);
        WorkerFixture fixture = fixture(
                edit,
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_e", EDIT, Map.of("path", "reports/q3.md")))
                        .respond(r -> r.text("需要确认的修改已跳过"))
                        .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        // 子线程的策略：父智能体的规则（含对 reports/** 的显式 ask）经子线程改写
        SubagentSpec writer = new SubagentSpec(
                "writer",
                "改文档的子智能体",
                ModelSpec.of("mdl_test"),
                "你是子智能体",
                Set.of(),
                List.of(),
                PermissionPolicy.empty(),
                10,
                Duration.ofSeconds(60));
        PermissionPolicy childPolicy = PermissionSynthesizer.subagents(
                        new PermissionLayers(List.of(), List.of(), askForReports(), List.of(), Interaction.ATTENDED),
                        List.of(writer))
                .get("writer");
        assertThat(childPolicy.mode()).isEqualTo(PermissionMode.DONT_ASK);
        fixture.post(node, session, message("交给子智能体改报告", Delivery.QUEUE));

        // 在本节点认领本轮，用子线程的策略直接驱动内核（T13 的 spawn_subagent 之前，验证改写本身）
        TurnExecution execution = node.claim();
        ClaimedTurn turn = execution.turn();
        TurnContext context = new TurnContext(
                PlatformContext.builder()
                        .tenantId(turn.tenantId())
                        .projectId(turn.projectId())
                        .sessionId(turn.sessionId())
                        .threadId(turn.threadId())
                        .turnId(turn.turnId())
                        .leaseEpoch(turn.epoch())
                        .interaction(Interaction.ATTENDED)
                        .build(),
                "u_1024",
                fixture.spec.agentVersionId(),
                fixture.environment.environmentId(),
                fixture.environment.revision(),
                childPolicy,
                Map.of(),
                List.of(),
                null);
        List<EngineEvent> events = node.engine()
                .runTurn(context, TestSessions.messages("改季度报告"))
                .collectList()
                .block(WorkerNode.RUN_TIMEOUT);

        assertThat(events).last().isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(events).noneMatch(SessionEvent.StatusRequiresAction.class::isInstance);
        assertThat(edit.calls()).isEmpty();
        assertThat(fixture.model.request(1).toolResults())
                .singleElement()
                .satisfies(block -> assertThat(block.getState()).isEqualTo(ToolResultState.DENIED));
    }

    @Test
    void contentRulesMatchThroughMatchRuleAndPlatformSafetyRulesWin() {
        PathRuleTool write = PathRuleTool.writing(PlatformToolNames.WRITE_FILE);
        PathRuleTool read = PathRuleTool.writing(PlatformToolNames.READ_FILE);
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall(
                                        "call_r", PlatformToolNames.WRITE_FILE, Map.of("path", "reports/q3.md"))
                                .toolCall("call_a", PlatformToolNames.WRITE_FILE, Map.of("path", "AGENTS.md"))
                                .toolCall("call_s", PlatformToolNames.WRITE_FILE, Map.of("path", "skills/x/SKILL.md"))
                                .toolCall("call_e", PlatformToolNames.READ_FILE, Map.of("path", ".env"))
                                .toolCall("call_k", PlatformToolNames.READ_FILE, Map.of("path", "config/id_rsa"))
                                .toolCall("call_o", PlatformToolNames.READ_FILE, Map.of("path", "docs/readme.md")))
                        .respond(r -> r.text("完成"))
                        .build(),
                agent -> agent.permissions(new PermissionPolicy(
                        PermissionMode.BYPASS,
                        List.of(new PermissionRule(
                                PlatformToolNames.WRITE_FILE,
                                null,
                                PermissionBehavior.ALLOW,
                                PolicyLayer.AGENT_VERSION)))));
        fixture.extraTools.add(write);
        fixture.extraTools.add(read);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("整理文件", Delivery.QUEUE));

        node.claimAndRun();

        assertThat(write.calledPaths()).containsExactly("reports/q3.md");
        assertThat(read.calledPaths()).containsExactly("docs/readme.md");
        assertThat(ofType(fixture.events(node, session), EventType.AGENT_TOOL_RESULT))
                .filteredOn(e -> e.data().contains("\"permission\": \"denied\""))
                .hasSize(4);
    }

    private static WorkerFixture fixture(PathRuleTool tool, ScriptedModel model) {
        WorkerFixture fixture = new WorkerFixture(model, agent -> agent.permissions(askForReports()));
        fixture.extraTools.add(tool);
        return fixture;
    }
}
