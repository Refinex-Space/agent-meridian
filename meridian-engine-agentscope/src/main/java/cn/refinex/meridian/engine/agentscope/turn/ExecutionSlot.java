package cn.refinex.meridian.engine.agentscope.turn;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一次执行（主线程的一轮或一次子智能体执行）的资源槽：{@link #attach} 与 {@link #close} 无论谁先发生，结束动作都恰好执行一次。
 *
 * <p>用法：准备步骤在虚拟线程上执行，完成后 {@code attach}；{@code usingWhen} 的清理与 {@code doOnCancel} 都调 {@code close}。
 * 订阅在准备步骤仍在执行时被取消，{@code usingWhen} 拿不到资源，由 {@code attach} 发现槽已关闭后立即结束，避免实例与登记泄漏。
 *
 * <p>线程安全：两个方法互斥，结束动作在锁外执行。
 *
 * @param <T> 准备好的执行
 */
final class ExecutionSlot<T> {

    private final Consumer<T> finisher;
    private T execution;
    private boolean closed;

    /**
     * 创建资源槽。
     *
     * @param finisher 结束动作：只清理本机内存与登记，不写任何数据，所以取消时也可以执行
     */
    ExecutionSlot(Consumer<T> finisher) {
        this.finisher = Objects.requireNonNull(finisher, "finisher");
    }

    /**
     * 登记准备好的执行；槽已关闭（订阅已被取消）时立即结束它。
     *
     * @param prepared 准备好的执行
     * @return 同一个执行
     */
    T attach(T prepared) {
        synchronized (this) {
            if (!closed) {
                execution = prepared;
                return prepared;
            }
        }
        finisher.accept(prepared);
        return prepared;
    }

    /** 关闭槽并结束已登记的执行，可以重复调用。 */
    void close() {
        T toFinish;
        synchronized (this) {
            closed = true;
            toFinish = execution;
            execution = null;
        }
        if (toFinish != null) {
            finisher.accept(toFinish);
        }
    }
}
