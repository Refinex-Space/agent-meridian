package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent.RecoveryDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 恢复轮的生成规则（架构设计「待处理动作」）。纯逻辑，不访问数据库。
 *
 * <ol>
 *   <li>一轮的全部动作都有了结论（回应、超时、中断或自动拒绝）才生成恢复轮，输入只包含这些结论。
 *   <li>既有待确认的调用（{@code tool_confirmation}、{@code plan_exit}）又有自定义工具时，先生成 {@code confirmations} 轮：
 *       AgentScope 只要发现待确认调用就走确认分支，同批的工具结果不会被处理。自定义工具的结论留着，确认轮再次停在
 *       {@code TOOL_SUSPENDED} 时由平台直接交出，生成 {@code custom_tool_results} 轮。
 *   <li>{@code recovery_confirmation} 生成恢复轮：恢复说明在恢复认领时生成的说明上，把被确认的调用标为 {@code RETRIED}（选
 *       {@code retry}，执行时由平台用原幂等键重新执行并补上结果摘要）或 {@code SKIPPED}。恢复方式为 {@code resume_saved_state}
 *       时输入是 {@code RecoveryOnly}；为 {@code replay_input}（进程被强杀，状态里没有本轮的输入）时重放原轮输入并附上说明，
 *       否则会丢掉用户消息（登记事项 D13，ADR-0031）。
 *   <li>任一结论来自中断时，恢复轮带着中断请求入队：把结论写进上下文后停止（架构设计「消息投递」）。
 * </ol>
 *
 * <p>线程安全：无状态。
 */
public final class ResumptionPlanner {

    private ResumptionPlanner() {}

    /**
     * 按一轮的动作生成下一个恢复轮。
     *
     * @param actions 等待回应的轮次的全部动作
     * @param awaitingInput 等待回应的轮次自己的输入（{@code turn_inputs}），{@code replay_input} 模式下重放
     * @return 恢复轮；还有动作在等待回应、或全部结论都已交出时为空
     */
    public static Optional<Resumption> plan(List<ResolvableAction> actions, TurnInput awaitingInput) {
        Objects.requireNonNull(awaitingInput, "awaitingInput");
        if (actions.stream().anyMatch(a -> !a.concluded() && a.action().isPending())) {
            return Optional.empty();
        }
        List<ResolvableAction> open =
                actions.stream().filter(a -> a.concluded() && !a.consumed()).toList();
        boolean interrupted =
                actions.stream().anyMatch(a -> a.concluded() && a.conclusion().origin() == ConclusionOrigin.INTERRUPT);
        List<ResolvableAction> confirmations =
                ofTypes(open, PendingAction.Type.TOOL_CONFIRMATION, PendingAction.Type.PLAN_EXIT);
        if (!confirmations.isEmpty()) {
            return Optional.of(resumption(
                    new TurnInput.Confirmations(
                            confirmations.stream()
                                    .map(ResumptionPlanner::confirmation)
                                    .toList(),
                            null),
                    confirmations,
                    interrupted));
        }
        List<ResolvableAction> customs = ofTypes(open, PendingAction.Type.CUSTOM_TOOL_RESULT);
        if (!customs.isEmpty()) {
            return Optional.of(resumption(
                    new TurnInput.CustomToolResults(
                            customs.stream()
                                    .map(ResumptionPlanner::customResult)
                                    .toList(),
                            null),
                    customs,
                    interrupted));
        }
        List<ResolvableAction> recoveries = ofTypes(open, PendingAction.Type.RECOVERY_CONFIRMATION);
        if (!recoveries.isEmpty()) {
            return Optional.of(resumption(recoveryInput(recoveries, awaitingInput), recoveries, interrupted));
        }
        return Optional.empty();
    }

    /**
     * 把确认类结论翻译为内核的工具确认：批准时按审批人修改后的入参（没有修改时按原入参）执行，"记住此决定"的规则本轮立即生效。
     *
     * @param action 已有结论的确认类动作
     * @return 工具确认
     */
    static ToolConfirmation confirmation(ResolvableAction action) {
        StoredPendingAction stored = action.action();
        ActionConclusion conclusion = action.conclusion();
        Map<String, Object> input = conclusion.updatedInput() == null ? stored.input() : conclusion.updatedInput();
        return new ToolConfirmation(
                stored.toolUseId(),
                stored.toolName(),
                input,
                conclusion.isApproved(),
                conclusion.denyReason(),
                RememberedRules.of(stored.toolName(), conclusion).stream().toList());
    }

    private static CustomToolResult customResult(ResolvableAction action) {
        ActionConclusion conclusion = action.conclusion();
        return new CustomToolResult(
                action.action().toolUseId(),
                action.action().toolName(),
                conclusion.content(),
                Boolean.TRUE.equals(conclusion.error()));
    }

    private static TurnInput recoveryInput(List<ResolvableAction> recoveries, TurnInput awaitingInput) {
        ResolvableAction first = recoveries.getFirst();
        Map<String, RecoveredToolCall> entries = new LinkedHashMap<>();
        if (first.recoveryNote() != null) {
            first.recoveryNote().toolCalls().forEach(call -> entries.put(call.toolUseId(), call));
        }
        for (ResolvableAction recovery : recoveries) {
            StoredPendingAction stored = recovery.action();
            Outcome outcome =
                    recovery.conclusion().decision() == RecoveryDecision.RETRY ? Outcome.RETRIED : Outcome.SKIPPED;
            entries.put(
                    stored.toolUseId(), new RecoveredToolCall(stored.toolUseId(), stored.toolName(), outcome, null));
        }
        RecoveryNote note = new RecoveryNote(new ArrayList<>(entries.values()));
        if (first.recoveryMode() == RecoveryOrigin.Mode.REPLAY_INPUT
                && !(awaitingInput instanceof TurnInput.RecoveryOnly)) {
            return withNote(awaitingInput, note);
        }
        return new TurnInput.RecoveryOnly(note);
    }

    /**
     * 给输入附上恢复说明，类型不变。
     *
     * @param input 输入
     * @param note 恢复说明
     * @return 新输入
     */
    public static TurnInput withNote(TurnInput input, RecoveryNote note) {
        return switch (input) {
            case TurnInput.NewMessages m -> new TurnInput.NewMessages(m.messages(), note);
            case TurnInput.Confirmations c -> new TurnInput.Confirmations(c.confirmations(), note);
            case TurnInput.CustomToolResults r -> new TurnInput.CustomToolResults(r.results(), note);
            case TurnInput.RecoveryOnly _ -> new TurnInput.RecoveryOnly(note);
        };
    }

    private static List<ResolvableAction> ofTypes(List<ResolvableAction> actions, PendingAction.Type... types) {
        List<PendingAction.Type> wanted = List.of(types);
        return actions.stream().filter(a -> wanted.contains(a.action().type())).toList();
    }

    private static Resumption resumption(TurnInput input, List<ResolvableAction> consumed, boolean interrupted) {
        return new Resumption(input, consumed.stream().map(a -> a.action().id()).toList(), interrupted);
    }

    /**
     * 下一个恢复轮。
     *
     * @param input 恢复轮的输入
     * @param consumedActionIds 本轮交出结论的动作
     * @param interruptRequested 是否带着中断请求入队
     */
    public record Resumption(TurnInput input, List<String> consumedActionIds, boolean interruptRequested) {

        /**
         * 创建恢复轮。
         *
         * @throws NullPointerException 缺少输入时
         */
        public Resumption {
            Objects.requireNonNull(input, "input");
            consumedActionIds = List.copyOf(consumedActionIds);
        }
    }
}
