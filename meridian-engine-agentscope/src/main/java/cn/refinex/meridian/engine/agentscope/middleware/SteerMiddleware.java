package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.turn.TurnInputTranslator;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * steer 消息（{@code onReasoning}）：每次推理前经 {@link InboxPort} 取出本线程 {@code delivery=steer} 的待投递消息，追加到本次推理
 * 输入与 {@code AgentState} 上下文，见架构设计「平台中间件」「消息投递」。
 *
 * <p>取出与标记在 runtime 的一个事务里完成：记录 {@code consumed_turn_id} 与 {@code consumed_epoch}（本次执行的租约 epoch），
 * 写 {@code session.message_consumed}，写入带租约条件。本轮在注入前就结束时，消息仍是待投递，轮次结束时与 queue 消息一起合并成
 * 下一轮（降级为 queue）。进程被强杀、本次执行的内容没有保存时，恢复认领按 {@code consumed_epoch} 重新投递它们（ADR-0028）。
 *
 * <p>顺序：必须在批次 checkpoint（80）之外，注入的消息才进入该次推理前保存的 checkpoint，否则强杀后从 checkpoint 续跑会丢失它
 * （ADR-0028 第 3 条）；在中断检查点（88）之内。子智能体不挂载本中间件（架构设计「子智能体」第 3 条）。
 *
 * <p>线程安全：无状态。
 */
public final class SteerMiddleware implements MiddlewareBase {

    /** 中间件顺序：中断检查点（88）之内、批次 checkpoint（80）之外。 */
    public static final int ORDER = 85;

    /** 注入的消息在元数据中的标记键，值为对应的 {@code user.message} 事件 ID。 */
    public static final String METADATA_KEY = "meridian_steer_event_id";

    private final InboxPort inbox;

    /**
     * 创建中间件。
     *
     * @param inbox 收件箱端口
     */
    public SteerMiddleware(InboxPort inbox) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            PlatformContext platform = ctx == null ? null : ctx.get(PlatformContext.class);
            if (platform == null || platform.turnId() == null || platform.leaseEpoch() == null) {
                return next.apply(input);
            }
            ThreadRef thread = new ThreadRef(platform.sessionId(), platform.threadId());
            return inbox.consumeSteer(thread, platform.turnId(), platform.leaseEpoch())
                    .contextWrite(PlatformContexts.write(platform))
                    .flatMapMany(messages -> next.apply(messages.isEmpty() ? input : inject(ctx, input, messages)));
        });
    }

    private static ReasoningInput inject(RuntimeContext ctx, ReasoningInput input, List<InboundMessage> steered) {
        List<Msg> messages = new ArrayList<>(input.messages());
        AgentState state = ctx.getAgentState();
        for (InboundMessage message : steered) {
            Msg msg = message(message);
            if (state != null) {
                state.contextMutable().add(msg);
            }
            messages.add(msg);
        }
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /**
     * 把一条 steer 消息翻译为用户消息，内容块与普通用户消息相同。
     *
     * @param message steer 消息
     * @return 用户消息，元数据带 {@link #METADATA_KEY}
     */
    static Msg message(InboundMessage message) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(message.content().stream()
                        .map(TurnInputTranslator::contentBlock)
                        .toList())
                .metadata(Map.of(METADATA_KEY, message.eventId()))
                .build();
    }
}
