package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 认领时已有中断请求的一轮，在第一次推理前按用户中断结束（{@code onReasoning}），见架构设计「消息投递」中"等待回应时收到
 * {@code user.interrupt}：对所有待处理动作生成拒绝结果，运行一个恢复轮后停止"。
 *
 * <p>做法是在推理开始前以 {@link InterruptedException} 结束本次推理：AgentScope 2.0.4 的推理入口把它当作用户中断，走
 * {@code handleInterrupt}（补齐悬空的工具调用结果、追加中断说明、保存状态），本轮以 {@code GenerateReason.INTERRUPTED}
 * 结束。这样本轮的输入（拒绝结果、自定义工具的错误结果、恢复说明）已经写进上下文，智能体不再发起新的模型调用。它比在
 * 执行中途调用 {@code interrupt} 确定：那条路径要等调用被受理、看到第一个事件后才能送达，可能已经开始模型调用。
 *
 * <p>顺序：在恢复说明（90）之内，恢复说明先写进状态再中断；在 steer（85）之外，未注入的 steer 留在收件箱，本轮结束后降级为
 * queue。线程安全：无状态；每轮的标记在 {@link PendingInterrupt} 中。
 */
public final class InterruptCheckpointMiddleware implements MiddlewareBase {

    /** 中间件顺序：恢复说明（90）之内、steer（85）之外。 */
    public static final int ORDER = 88;

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> PendingInterrupt.take(ctx)
                ? Flux.error(new InterruptedException("本轮带着中断请求开始，在推理前停止"))
                : next.apply(input));
    }
}
