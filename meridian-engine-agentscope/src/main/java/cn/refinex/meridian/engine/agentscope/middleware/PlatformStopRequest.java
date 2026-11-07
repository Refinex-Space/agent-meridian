package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.spi.event.StopReason;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequestStopEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台中间件请求停止本轮时记录的停止原因，每轮一个，由引擎以类型键放入 {@code RuntimeContext}。
 *
 * <p>架构设计「状态机」规定：以 {@code REASONING_STOP_REQUESTED}、{@code ACTING_STOP_REQUESTED}、
 * {@code MIDDLEWARE_STOP_REQUESTED} 结束时，平台中间件发起的取它记录的原因，否则为 {@code end_turn}。
 * AgentScope 只把 {@code RequestStopEvent} 合并进 {@code GenerateReason}，并不总把事件发布到流上
 * （例如"全部工具被拒绝"的路径），所以原因记在这里，由事件映射在流结束时读取（ADR-0026）。
 *
 * <p>线程安全：第一次请求生效，与 AgentScope 取第一个 {@code RequestStopEvent} 一致。
 */
public final class PlatformStopRequest {

    /** {@code RequestStopEvent} 的 {@code reason} 前缀，便于在日志中识别平台发起的停止。 */
    static final String REASON_PREFIX = "meridian:";

    private final AtomicReference<StopReason> reason = new AtomicReference<>();

    /**
     * 记录停止原因并返回要由中间件发出的停止事件。
     *
     * @param ctx 本次调用的上下文，必须带有引擎放入的本轮记录
     * @param stopReason 本轮的停止原因
     * @return 中间件在 {@code onReasoning} 或 {@code onActing} 的事件流中发出的事件
     * @throws IllegalStateException 上下文中没有本轮记录时（不是经平台引擎执行的调用）
     */
    public static RequestStopEvent request(RuntimeContext ctx, StopReason stopReason) {
        Objects.requireNonNull(stopReason, "stopReason");
        PlatformStopRequest record = ctx.get(PlatformStopRequest.class);
        if (record == null) {
            throw new IllegalStateException("RuntimeContext 中没有 PlatformStopRequest，调用不是经平台引擎执行的");
        }
        record.reason.compareAndSet(null, stopReason);
        return new RequestStopEvent(REASON_PREFIX + stopReason.value());
    }

    /**
     * 平台中间件记录的停止原因。
     *
     * @return 停止原因；没有平台中间件请求停止时为空
     */
    public Optional<StopReason> reason() {
        return Optional.ofNullable(reason.get());
    }
}
