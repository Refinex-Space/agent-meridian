package cn.refinex.meridian.runtime.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.RecoveryDecision;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 恢复轮的生成规则（架构设计「待处理动作」，ADR-0031）。 */
class ResumptionPlannerTest {

    private static final TurnInput ORIGINAL = new TurnInput.NewMessages(
            List.of(new InboundMessage("evt_1", List.of(new MessageContent.Text("扣一笔款")))), null);

    @Test
    void nothingIsResumedWhileAnActionIsStillPending() {
        List<ResolvableAction> actions = List.of(
                concluded("act_1", PendingAction.Type.TOOL_CONFIRMATION, approve(RememberScope.NONE)),
                pending("act_2", PendingAction.Type.TOOL_CONFIRMATION));

        assertThat(ResumptionPlanner.plan(actions, ORIGINAL)).isEmpty();
    }

    @Test
    void confirmationsComeFirstAndCustomToolResultsWaitForTheNextSuspension() {
        ResolvableAction confirm =
                concluded("act_1", PendingAction.Type.TOOL_CONFIRMATION, approve(RememberScope.NONE));
        ResolvableAction custom = concluded(
                "act_2",
                PendingAction.Type.CUSTOM_TOOL_RESULT,
                ActionConclusion.of(
                        new UserEvent.CustomToolResult("act_2", List.of(new MessageContent.Text("已通知")), false)));

        ResumptionPlanner.Resumption first =
                ResumptionPlanner.plan(List.of(confirm, custom), ORIGINAL).orElseThrow();

        assertThat(first.input()).isInstanceOf(TurnInput.Confirmations.class);
        assertThat(first.consumedActionIds()).containsExactly("act_1");

        ResumptionPlanner.Resumption second = ResumptionPlanner.plan(
                        List.of(consumed(confirm, "trn_2"), custom), ORIGINAL)
                .orElseThrow();
        assertThat(second.input())
                .isEqualTo(new TurnInput.CustomToolResults(
                        List.of(new CustomToolResult(
                                "call_act_2", "tool_act_2", List.of(new MessageContent.Text("已通知")), false)),
                        null));
        assertThat(second.consumedActionIds()).containsExactly("act_2");
        assertThat(ResumptionPlanner.plan(List.of(consumed(confirm, "trn_2"), consumed(custom, "trn_3")), ORIGINAL))
                .as("全部结论都已交出")
                .isEmpty();
    }

    @Test
    void approvalUsesTheUpdatedInputAndCarriesTheRememberedRuleIntoThisTurn() {
        ActionConclusion conclusion = ActionConclusion.of(
                new UserEvent.ToolConfirmation("act_1", true, Map.of("amount", 10), null, RememberScope.PROJECT));

        TurnInput.Confirmations input = (TurnInput.Confirmations) ResumptionPlanner.plan(
                        List.of(concluded("act_1", PendingAction.Type.TOOL_CONFIRMATION, conclusion)), ORIGINAL)
                .orElseThrow()
                .input();

        assertThat(input.confirmations())
                .containsExactly(new ToolConfirmation(
                        "call_act_1",
                        "tool_act_1",
                        Map.of("amount", 10),
                        true,
                        null,
                        List.of(new PermissionRule(
                                "tool_act_1", null, PermissionBehavior.ALLOW, PolicyLayer.PROJECT))));
    }

    @Test
    void platformConclusionsDenyWithAnExplanationAndAnInterruptRequestsAStop() {
        ResolvableAction interrupted = concluded(
                "act_1",
                PendingAction.Type.TOOL_CONFIRMATION,
                ActionConclusion.declined(PendingAction.Type.TOOL_CONFIRMATION, ConclusionOrigin.INTERRUPT));

        ResumptionPlanner.Resumption resumption =
                ResumptionPlanner.plan(List.of(interrupted), ORIGINAL).orElseThrow();

        assertThat(resumption.interruptRequested()).isTrue();
        ToolConfirmation denial =
                ((TurnInput.Confirmations) resumption.input()).confirmations().getFirst();
        assertThat(denial.approved()).isFalse();
        assertThat(denial.denyReason()).contains("用户中断");
        assertThat(ActionConclusion.declined(PendingAction.Type.PLAN_EXIT, ConclusionOrigin.TIMEOUT)
                        .denyReason())
                .contains("保持计划模式");
        assertThat(ActionConclusion.declined(PendingAction.Type.CUSTOM_TOOL_RESULT, ConclusionOrigin.TIMEOUT)
                        .content())
                .containsExactly(new MessageContent.Text("timeout"));
        assertThat(ActionConclusion.declined(PendingAction.Type.RECOVERY_CONFIRMATION, ConclusionOrigin.TIMEOUT)
                        .decision())
                .isEqualTo(RecoveryDecision.SKIP);
    }

    @Test
    void recoveryConfirmationsMarkTheNoteAndReplayTheInputInReplayMode() {
        RecoveryNote note = new RecoveryNote(List.of(
                new RecoveredToolCall("call_l", "lookup", Outcome.SUCCEEDED, "余额 100"),
                new RecoveredToolCall("call_act_1", "tool_act_1", Outcome.UNKNOWN, null),
                new RecoveredToolCall("call_act_2", "tool_act_2", Outcome.UNKNOWN, null)));
        List<ResolvableAction> replay = List.of(
                recovery("act_1", RecoveryDecision.RETRY, note, RecoveryOrigin.Mode.REPLAY_INPUT),
                recovery("act_2", RecoveryDecision.SKIP, note, RecoveryOrigin.Mode.REPLAY_INPUT));

        TurnInput replayed =
                ResumptionPlanner.plan(replay, ORIGINAL).orElseThrow().input();

        RecoveryNote expected = new RecoveryNote(List.of(
                new RecoveredToolCall("call_l", "lookup", Outcome.SUCCEEDED, "余额 100"),
                new RecoveredToolCall("call_act_1", "tool_act_1", Outcome.RETRIED, null),
                new RecoveredToolCall("call_act_2", "tool_act_2", Outcome.SKIPPED, null)));
        assertThat(replayed)
                .as("replay_input：重放原轮输入，否则会丢掉用户消息（D13）")
                .isEqualTo(new TurnInput.NewMessages(((TurnInput.NewMessages) ORIGINAL).messages(), expected));

        List<ResolvableAction> resume =
                List.of(recovery("act_1", RecoveryDecision.SKIP, note, RecoveryOrigin.Mode.RESUME_SAVED_STATE));
        assertThat(ResumptionPlanner.plan(resume, ORIGINAL).orElseThrow().input())
                .isInstanceOf(TurnInput.RecoveryOnly.class);
    }

    private static ActionConclusion approve(RememberScope remember) {
        return ActionConclusion.of(new UserEvent.ToolConfirmation("act", true, null, null, remember));
    }

    private static StoredPendingAction stored(String id, PendingAction.Type type, PendingActionStatus status) {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        return new StoredPendingAction(
                id,
                "ses_1",
                "thr_1",
                "trn_1",
                type,
                status,
                "call_" + id,
                "tool_" + id,
                Map.of("text", "原入参"),
                now.plusSeconds(1800),
                now,
                status == PendingActionStatus.PENDING ? null : now);
    }

    private static ResolvableAction pending(String id, PendingAction.Type type) {
        return new ResolvableAction(stored(id, type, PendingActionStatus.PENDING), null, null, null, null, null);
    }

    private static ResolvableAction concluded(String id, PendingAction.Type type, ActionConclusion conclusion) {
        return new ResolvableAction(stored(id, type, PendingActionStatus.RESOLVED), conclusion, null, null, null, null);
    }

    private static ResolvableAction consumed(ResolvableAction action, String turnId) {
        return new ResolvableAction(
                action.action(),
                action.conclusion(),
                turnId,
                action.idempotencyKey(),
                action.recoveryNote(),
                action.recoveryMode());
    }

    private static ResolvableAction recovery(
            String id, RecoveryDecision decision, RecoveryNote note, RecoveryOrigin.Mode mode) {
        return new ResolvableAction(
                stored(id, PendingAction.Type.RECOVERY_CONFIRMATION, PendingActionStatus.RESOLVED),
                ActionConclusion.of(new UserEvent.RecoveryConfirmation(id, decision)),
                null,
                "idem-" + id,
                note,
                mode);
    }
}
