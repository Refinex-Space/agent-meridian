package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin.Mode;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 崩溃恢复的判定：输入是恢复认领时的状态与账本快照，输出是恢复方式与恢复说明（架构设计「崩溃恢复」，ADR-0028）。纯逻辑，
 * 不访问数据库。
 *
 * <p>判定规则，按顺序：
 * <ol>
 *   <li>{@code recovery_attempt} 达到 {@value #MAX_RECOVERY_ATTEMPTS}：本轮已经失败两次，不再执行，以 {@code stop_reason=error}
 *       结束（{@link RecoveryDecision.Exhausted}）。{@code claim_count} 不参与判断。
 *   <li>本轮在线程状态中写过内容（出错、中断、停机时 AgentScope 保存的中途状态，或批次 checkpoint）：不重放输入，输入为
 *       {@code RecoveryOnly}（{@link Mode#RESUME_SAVED_STATE}）；从未写过（进程被强杀）：重放 {@code turn_inputs} 中的完整输入
 *       （{@link Mode#REPLAY_INPUT}）。判断依据是"本轮的内容写入"，而不是只比较 {@code content_epoch}：epoch 每轮从 1 开始，
 *       上一轮的写入会与本轮的 epoch 相同。
 *   <li>失败那次执行的内容没有保存（本轮没有内容写入，或最近的写入来自更早的一次执行）时，它消费的 steer 消息不在状态中，
 *       重新投递 {@code consumed_epoch} 等于失败 epoch 的 steer。
 *   <li>此前各次执行中状态仍为 {@code running} 的 {@code non_idempotent} 调用（状态未知、不能重放）：本轮不执行，创建
 *       {@code recovery_confirmation} 待处理动作（{@link RecoveryDecision.AwaitConfirmation}）。
 *   <li>恢复说明列出此前各次执行中已成功的调用（附结果摘要）与状态未知的调用；失败、被拒绝、被中断的调用状态已知，不列出。
 *       重放的输入原本带有恢复说明时（例如回应恢复确认后的恢复轮），合并两者，同一调用以账本为准。
 * </ol>
 *
 * <p>线程安全：无状态。
 */
public final class RecoveryPlanner {

    /** 恢复次数上限：{@code recovery_attempt} 达到它时轮次以 {@code stop_reason=error} 结束（架构设计「崩溃恢复」第 5 条）。 */
    public static final int MAX_RECOVERY_ATTEMPTS = 2;

    private RecoveryPlanner() {}

    /**
     * 判定恢复方式。
     *
     * @param snapshot 恢复认领时的快照
     * @return 结论
     */
    public static RecoveryDecision decide(RecoverySnapshot snapshot) {
        long failedEpoch = snapshot.failedEpoch();
        boolean contentSaved = snapshot.turnContentEpoch().isPresent();
        boolean failedExecutionSaved =
                contentSaved && snapshot.turnContentEpoch().getAsLong() == failedEpoch;
        boolean redeliverSteer = !failedExecutionSaved;
        if (snapshot.recoveryAttempt() >= MAX_RECOVERY_ATTEMPTS) {
            return new RecoveryDecision.Exhausted(failedEpoch, snapshot.recoveryAttempt(), redeliverSteer);
        }
        Mode mode = contentSaved ? Mode.RESUME_SAVED_STATE : Mode.REPLAY_INPUT;
        RecoveryOrigin origin = new RecoveryOrigin(failedEpoch, snapshot.recoveryAttempt(), mode);
        List<ToolCallRecord> calls = latestPerToolUse(snapshot.calls());
        List<ToolCallRecord> unknownNonIdempotent = calls.stream()
                .filter(call -> call.status() == Status.RUNNING && call.sideEffect() == SideEffect.NON_IDEMPOTENT)
                .toList();
        if (!unknownNonIdempotent.isEmpty()) {
            return new RecoveryDecision.AwaitConfirmation(origin, redeliverSteer, unknownNonIdempotent);
        }
        RecoveryNote note = note(snapshot.input().recovery(), calls);
        TurnInput input =
                mode == Mode.RESUME_SAVED_STATE ? new TurnInput.RecoveryOnly(note) : withNote(snapshot.input(), note);
        return new RecoveryDecision.Resume(origin, redeliverSteer, input);
    }

    /** 同一工具调用 ID 在多次执行中出现时取最后一条，顺序按第一次出现。 */
    private static List<ToolCallRecord> latestPerToolUse(List<ToolCallRecord> calls) {
        Map<String, ToolCallRecord> latest = new LinkedHashMap<>();
        for (ToolCallRecord call : calls) {
            latest.put(call.toolUseId(), call);
        }
        return List.copyOf(latest.values());
    }

    private static RecoveryNote note(RecoveryNote original, List<ToolCallRecord> calls) {
        Map<String, RecoveredToolCall> entries = new LinkedHashMap<>();
        if (original != null) {
            original.toolCalls().forEach(call -> entries.put(call.toolUseId(), call));
        }
        for (ToolCallRecord call : calls) {
            Outcome outcome = switch (call.status()) {
                case SUCCEEDED -> Outcome.SUCCEEDED;
                case RUNNING -> Outcome.UNKNOWN;
                case FAILED, DENIED, INTERRUPTED -> null;
            };
            if (outcome != null) {
                String summary = outcome == Outcome.SUCCEEDED ? call.resultSummary() : null;
                entries.put(
                        call.toolUseId(), new RecoveredToolCall(call.toolUseId(), call.toolName(), outcome, summary));
            }
        }
        return new RecoveryNote(new ArrayList<>(entries.values()));
    }

    private static TurnInput withNote(TurnInput input, RecoveryNote note) {
        return switch (input) {
            case TurnInput.NewMessages m -> new TurnInput.NewMessages(m.messages(), note);
            case TurnInput.Confirmations c -> new TurnInput.Confirmations(c.confirmations(), note);
            case TurnInput.CustomToolResults r -> new TurnInput.CustomToolResults(r.results(), note);
            case TurnInput.RecoveryOnly _ -> new TurnInput.RecoveryOnly(note);
        };
    }
}
