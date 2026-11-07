package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 恢复说明（{@code onReasoning}）：恢复轮的第一次推理前，把平台生成的恢复说明作为一条用户消息追加到本次推理输入与
 * {@code AgentState} 上下文，见架构设计「平台中间件」「崩溃恢复」。
 *
 * <p>追加到 {@code AgentState} 是为了让说明随状态保存，之后的推理与下一次恢复都能看到；追加到推理输入是因为 AgentScope
 * 在推理前已经复制了上下文（{@code PreReasoningEvent}），只改状态影响不到本次模型调用。说明经 {@link RecoveryNoteSlot}
 * 传入，不走调用入参：停机中断后的下一次调用会丢弃全部入参。
 *
 * <p>线程安全：无状态。
 */
public final class RecoveryNoteMiddleware implements MiddlewareBase {

    /** 中间件顺序：在租约守卫（100）之内，在批次 checkpoint（80）之外，使恢复说明进入之后的 checkpoint。 */
    public static final int ORDER = 90;

    /** 恢复说明消息在元数据中的标记键，便于识别与排查。 */
    public static final String METADATA_KEY = "meridian_recovery_note";

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> RecoveryNoteSlot.take(ctx)
                .map(note -> next.apply(inject(ctx, input, message(note))))
                .orElseGet(() -> next.apply(input)));
    }

    private static ReasoningInput inject(RuntimeContext ctx, ReasoningInput input, Msg note) {
        AgentState state = ctx.getAgentState();
        if (state != null) {
            state.contextMutable().add(note);
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(note);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /**
     * 把恢复说明渲染为给模型看的用户消息。
     *
     * @param note 恢复说明
     * @return 消息，元数据带 {@link #METADATA_KEY}
     */
    static Msg message(RecoveryNote note) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text(note)).build()))
                .metadata(Map.of(METADATA_KEY, true))
                .build();
    }

    /**
     * 恢复说明的文本：说明发生了中断，逐个列出中断前工具调用的状态，要求智能体基于这些情况继续。
     *
     * @param note 恢复说明
     * @return 文本
     */
    static String text(RecoveryNote note) {
        StringBuilder text = new StringBuilder("[平台恢复说明] 上一次执行意外中断，平台已恢复本轮。");
        if (note.toolCalls().isEmpty()) {
            return text.append("中断前没有进行中的工具调用。请继续完成任务。").toString();
        }
        text.append("中断前的工具调用：");
        for (RecoveredToolCall call : note.toolCalls()) {
            text.append("\n- ")
                    .append(call.toolName())
                    .append("（")
                    .append(call.toolUseId())
                    .append("）：");
            text.append(
                    switch (call.outcome()) {
                        case SUCCEEDED -> "已成功执行，不要重复执行。";
                        case UNKNOWN -> "状态未知，可能已经执行；确有需要时可以再次调用。";
                        case RETRIED -> "已按原幂等键重新执行。";
                        case SKIPPED -> "已跳过，没有重新执行。";
                    });
            if (call.resultSummary() != null && !call.resultSummary().isBlank()) {
                text.append("结果摘要：").append(call.resultSummary());
            }
        }
        return text.append("\n请基于以上情况继续完成任务。").toString();
    }
}
