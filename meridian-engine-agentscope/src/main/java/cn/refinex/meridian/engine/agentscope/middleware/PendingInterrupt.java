package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本轮在认领时已有中断请求的标记，由引擎在 {@code TurnContext.interruptRequested} 为真时以类型键放入 {@code RuntimeContext}，
 * {@link InterruptCheckpointMiddleware} 在第一次推理前取出（架构设计「消息投递」）。
 *
 * <p>线程安全：取出标记是原子的，只生效一次。
 */
public final class PendingInterrupt {

    private final AtomicBoolean taken = new AtomicBoolean();

    /**
     * 取出本次调用的中断标记，同一轮只返回一次 true。
     *
     * @param ctx 本次调用的上下文；可为空
     * @return 本轮带着中断请求开始、且尚未处理时为 true
     */
    static boolean take(RuntimeContext ctx) {
        PendingInterrupt pending = ctx == null ? null : ctx.get(PendingInterrupt.class);
        return pending != null && pending.taken.compareAndSet(false, true);
    }
}
