package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个恢复轮待注入的恢复说明，由引擎在构造本轮的 {@code RuntimeContext} 时以类型键放入，{@link RecoveryNoteMiddleware}
 * 在第一次推理前取出，只取一次（架构设计「崩溃恢复」）。
 *
 * <p>恢复说明不走调用入参：AgentScope 在停机中断后的下一次调用会丢弃全部入参。
 *
 * <p>线程安全：取出标记是原子的。
 */
public final class RecoveryNoteSlot {

    private final RecoveryNote note;
    private final AtomicBoolean taken = new AtomicBoolean();

    /**
     * 创建待注入的恢复说明。
     *
     * @param note 恢复说明；必填
     */
    public RecoveryNoteSlot(RecoveryNote note) {
        this.note = Objects.requireNonNull(note, "note");
    }

    /**
     * 取出本次调用待注入的恢复说明，同一轮只返回一次。
     *
     * @param ctx 本次调用的上下文；可为空
     * @return 恢复说明；不是恢复轮或已经注入过时为空
     */
    static Optional<RecoveryNote> take(RuntimeContext ctx) {
        RecoveryNoteSlot slot = ctx == null ? null : ctx.get(RecoveryNoteSlot.class);
        if (slot == null || !slot.taken.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(slot.note);
    }
}
