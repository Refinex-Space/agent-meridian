package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 租约守卫（{@code onReasoning}、{@code onActing}）：每次推理与每批工具执行前，向 {@link LeaseRegistry} 确认本节点仍持有
 * 该线程的租约且 epoch 与本次执行一致，否则以 {@link LeaseLostException} 失败，不再发起新的模型调用与副作用，见架构设计
 * 「平台中间件」「租约与 fencing」。
 *
 * <p>它是纵深防御：失去租约时 Worker 会取消订阅，状态写入也由 {@code FencedAgentStateStore} 在数据库中校验。
 * 子线程的 {@code PlatformContext} 带父轮次的 epoch，{@code LeaseRegistry} 对登记过的子线程返回父线程的租约。
 *
 * <p>线程安全：无状态；只读内存中的租约，不阻塞。
 */
public final class LeaseGuardMiddleware implements MiddlewareBase {

    /** 中间件顺序：在追踪之内，位于 harness 自带的中间件（压缩等，默认 1）与工具 span 之外。 */
    public static final int ORDER = 100;

    private final LeaseRegistry leases;

    /**
     * 创建租约守卫。
     *
     * @param leases 本节点持有的租约
     */
    public LeaseGuardMiddleware(LeaseRegistry leases) {
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            verify(ctx);
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            verify(ctx);
            return next.apply(input);
        });
    }

    private void verify(RuntimeContext ctx) {
        PlatformContext platform = ctx == null ? null : ctx.get(PlatformContext.class);
        if (platform == null || platform.threadId() == null || platform.leaseEpoch() == null) {
            throw new LeaseLostException("调用没有带租约的 PlatformContext，拒绝继续执行");
        }
        long expected = platform.leaseEpoch();
        boolean valid = leases.find(platform.threadId())
                .map(lease -> lease.epoch() == expected)
                .orElse(false);
        if (!valid) {
            throw new LeaseLostException("线程 " + platform.threadId() + " 在本节点的租约已不是 epoch " + expected + "，停止执行");
        }
    }
}
