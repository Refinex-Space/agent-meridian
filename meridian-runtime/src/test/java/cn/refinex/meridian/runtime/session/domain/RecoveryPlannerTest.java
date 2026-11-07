package cn.refinex.meridian.runtime.session.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin.Mode;
import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 恢复判定（架构设计「崩溃恢复」，ADR-0028）与轮次结束映射的单元测试。 */
class RecoveryPlannerTest {

    private static final TurnInput.NewMessages INPUT = new TurnInput.NewMessages(
            List.of(new InboundMessage("evt_1", List.of(new MessageContent.Text("查订单")))), null);

    @Test
    void killedBeforeAnyContentWriteReplaysTheFullInputAndRedeliversSteer() {
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                INPUT,
                2,
                1,
                OptionalLong.empty(),
                List.of(
                        call("call_a", SideEffect.IDEMPOTENT, Status.SUCCEEDED, 1, "a 的结果"),
                        call("call_b", SideEffect.NONE, Status.RUNNING, 1, null))));

        RecoveryDecision.Resume resume = (RecoveryDecision.Resume) decision;
        assertThat(resume.origin()).isEqualTo(new RecoveryOrigin(1, 1, Mode.REPLAY_INPUT));
        assertThat(resume.redeliverSteer()).isTrue();
        assertThat(resume.input()).isInstanceOf(TurnInput.NewMessages.class);
        assertThat(((TurnInput.NewMessages) resume.input()).messages()).isEqualTo(INPUT.messages());
        assertThat(resume.note().toolCalls())
                .containsExactly(
                        new RecoveredToolCall("call_a", "tool", Outcome.SUCCEEDED, "a 的结果"),
                        new RecoveredToolCall("call_b", "tool", Outcome.UNKNOWN, null));
    }

    @Test
    void stateSavedByTheFailedExecutionResumesWithRecoveryOnlyAndKeepsSteer() {
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                INPUT,
                2,
                1,
                OptionalLong.of(1),
                List.of(call("call_a", SideEffect.NON_IDEMPOTENT, Status.SUCCEEDED, 1, "ok"))));

        RecoveryDecision.Resume resume = (RecoveryDecision.Resume) decision;
        assertThat(resume.origin().mode()).isEqualTo(Mode.RESUME_SAVED_STATE);
        assertThat(resume.redeliverSteer()).as("失败那次执行的内容已保存，它消费的 steer 在状态里").isFalse();
        assertThat(resume.input())
                .isEqualTo(new TurnInput.RecoveryOnly(
                        new RecoveryNote(List.of(new RecoveredToolCall("call_a", "tool", Outcome.SUCCEEDED, "ok")))));
    }

    @Test
    void stateSavedByAnEarlierExecutionOfTheSameTurnResumesWithoutReplayingButRedeliversSteer() {
        // epoch 1 节点下线时保存了中途状态，epoch 2 被强杀、没有写入内容：重放输入会重复用户消息
        RecoveryDecision decision =
                RecoveryPlanner.decide(new RecoverySnapshot(INPUT, 3, 1, OptionalLong.of(1), List.of()));

        RecoveryDecision.Resume resume = (RecoveryDecision.Resume) decision;
        assertThat(resume.origin()).isEqualTo(new RecoveryOrigin(2, 1, Mode.RESUME_SAVED_STATE));
        assertThat(resume.input()).isInstanceOf(TurnInput.RecoveryOnly.class);
        assertThat(resume.redeliverSteer()).isTrue();
    }

    @Test
    void gracefulShutdownDoesNotCountAsARecoveryAttempt() {
        RecoveryDecision decision =
                RecoveryPlanner.decide(new RecoverySnapshot(INPUT, 2, 0, OptionalLong.of(1), List.of()));

        assertThat(((RecoveryDecision.Resume) decision).origin())
                .isEqualTo(new RecoveryOrigin(1, 0, Mode.RESUME_SAVED_STATE));
    }

    @Test
    void unknownNonIdempotentCallsNeedHumanConfirmation() {
        ToolCallRecord unknownWrite = call("call_w", SideEffect.NON_IDEMPOTENT, Status.RUNNING, 1, null);
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                INPUT,
                2,
                1,
                OptionalLong.empty(),
                List.of(call("call_r", SideEffect.IDEMPOTENT, Status.RUNNING, 1, null), unknownWrite)));

        RecoveryDecision.AwaitConfirmation confirmation = (RecoveryDecision.AwaitConfirmation) decision;
        assertThat(confirmation.unknownCalls()).containsExactly(unknownWrite);
        assertThat(confirmation.origin().mode()).isEqualTo(Mode.REPLAY_INPUT);
        assertThat(confirmation.redeliverSteer()).isTrue();
    }

    @Test
    void finishedNonIdempotentCallsAreKnownAndDoNotNeedConfirmation() {
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                INPUT,
                2,
                1,
                OptionalLong.empty(),
                List.of(
                        call("call_ok", SideEffect.NON_IDEMPOTENT, Status.SUCCEEDED, 1, "写好了"),
                        call("call_failed", SideEffect.NON_IDEMPOTENT, Status.FAILED, 1, null),
                        call("call_denied", SideEffect.NON_IDEMPOTENT, Status.DENIED, 1, null))));

        assertThat(((RecoveryDecision.Resume) decision).note().toolCalls())
                .as("失败、被拒绝的调用状态已知，不列出")
                .containsExactly(new RecoveredToolCall("call_ok", "tool", Outcome.SUCCEEDED, "写好了"));
    }

    @Test
    void theRecoveryAttemptLimitEndsTheTurnBeforeAnythingElse() {
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                INPUT,
                3,
                RecoveryPlanner.MAX_RECOVERY_ATTEMPTS,
                OptionalLong.empty(),
                List.of(call("call_w", SideEffect.NON_IDEMPOTENT, Status.RUNNING, 2, null))));

        assertThat(decision).isEqualTo(new RecoveryDecision.Exhausted(2, 2, true));
    }

    @Test
    void theLatestRecordOfACallWinsAndAnExistingNoteIsMerged() {
        TurnInput.RecoveryOnly afterConfirmation = new TurnInput.RecoveryOnly(new RecoveryNote(List.of(
                new RecoveredToolCall("call_w", "tool", Outcome.RETRIED, "重试成功"),
                new RecoveredToolCall("call_x", "tool", Outcome.UNKNOWN, null))));
        RecoveryDecision decision = RecoveryPlanner.decide(new RecoverySnapshot(
                afterConfirmation,
                3,
                1,
                OptionalLong.empty(),
                List.of(
                        call("call_x", SideEffect.IDEMPOTENT, Status.RUNNING, 1, null),
                        call("call_x", SideEffect.IDEMPOTENT, Status.SUCCEEDED, 2, "x 的结果"))));

        assertThat(((RecoveryDecision.Resume) decision).input())
                .isEqualTo(new TurnInput.RecoveryOnly(new RecoveryNote(List.of(
                        new RecoveredToolCall("call_w", "tool", Outcome.RETRIED, "重试成功"),
                        new RecoveredToolCall("call_x", "tool", Outcome.SUCCEEDED, "x 的结果")))));
    }

    @Test
    void replayKeepsTheInputKindAndRejectsNonRecoveryEpochs() {
        TurnInput.CustomToolResults results = new TurnInput.CustomToolResults(
                List.of(new CustomToolResult("call_c", "query_order", List.of(new MessageContent.Text("已发货")), false)),
                null);

        RecoveryDecision decision =
                RecoveryPlanner.decide(new RecoverySnapshot(results, 2, 1, OptionalLong.empty(), List.of()));

        assertThat(((RecoveryDecision.Resume) decision).input())
                .isEqualTo(new TurnInput.CustomToolResults(results.results(), new RecoveryNote(List.of())));
        assertThatThrownBy(() -> new RecoverySnapshot(INPUT, 1, 0, OptionalLong.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopReasonsAndRescheduleReasonsMapToTurnOutcomes() {
        assertThat(TurnEnding.outcomeOf(StopReason.END_TURN)).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(TurnEnding.outcomeOf(StopReason.MAX_TURNS)).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(TurnEnding.outcomeOf(StopReason.TOOLS_DENIED)).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(TurnEnding.outcomeOf(StopReason.BUDGET_EXCEEDED)).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(TurnEnding.outcomeOf(StopReason.INTERRUPTED)).isEqualTo(TurnOutcome.INTERRUPTED);
        assertThat(TurnEnding.outcomeOf(StopReason.ERROR)).isEqualTo(TurnOutcome.FAILED);
        for (RescheduleReason reason : RescheduleReason.values()) {
            assertThat(TurnEnding.requeueReasonOf(reason).eventReason()).isEqualTo(reason);
        }
        assertThat(TurnEnding.requeueReasonOf(RescheduleReason.NODE_SHUTDOWN).countsAsRecovery())
                .isFalse();
        assertThat(TurnEnding.requeueReasonOf(RescheduleReason.RETRYABLE_ERROR))
                .isEqualTo(RequeueReason.RETRYABLE_ERROR);
    }

    private static ToolCallRecord call(String id, SideEffect sideEffect, Status status, long epoch, String summary) {
        return new ToolCallRecord(
                "tiv_" + id,
                id,
                "tool",
                sideEffect,
                status,
                epoch,
                sideEffect == SideEffect.NON_IDEMPOTENT ? "idem-" + id : null,
                Map.of("text", "x"),
                summary);
    }
}
