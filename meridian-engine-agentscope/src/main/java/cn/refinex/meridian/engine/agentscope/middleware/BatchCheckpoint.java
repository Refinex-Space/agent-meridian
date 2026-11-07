package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮中"有一批工具执行完、尚未做 checkpoint"的标记，由引擎在构造本轮的 {@code RuntimeContext} 时以类型键放入，
 * {@link BatchCheckpointMiddleware} 在每批工具执行完成时标记、在下一次推理前取走（P3，ADR-0028）。
 *
 * <p>线程安全：标记是原子的。
 */
public final class BatchCheckpoint {

    private final AtomicBoolean pending = new AtomicBoolean();

    /** 创建一轮的标记，初始没有待做的 checkpoint。 */
    public BatchCheckpoint() {
        // 标记由中间件在每批工具执行完成时设置
    }

    /** 一批工具执行完成。 */
    void batchCompleted() {
        pending.set(true);
    }

    /**
     * 取走待做的 checkpoint。
     *
     * @param ctx 本次调用的上下文；可为空
     * @return 上一批工具执行完、还没有做 checkpoint 时为 true，同一批只返回一次
     */
    static boolean takePending(RuntimeContext ctx) {
        BatchCheckpoint checkpoint = of(ctx);
        return checkpoint != null && checkpoint.pending.getAndSet(false);
    }

    /**
     * 取出本轮的标记。
     *
     * @param ctx 本次调用的上下文；可为空
     * @return 标记；不是经平台引擎执行的调用时为空
     */
    static BatchCheckpoint of(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(BatchCheckpoint.class);
    }
}
