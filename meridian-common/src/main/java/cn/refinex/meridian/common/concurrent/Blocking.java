package cn.refinex.meridian.common.concurrent;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 把阻塞调用调度到虚拟线程上执行，返回 {@link Mono}。runtime 中的 JDBC、对象存储、沙箱 HTTP 等阻塞操作
 * 一律经它调用，Reactor 线程上不允许直接阻塞（编码规范「并发与响应式」）。
 *
 * <p>订阅时，它从 Reactor Context 中取出 {@link PlatformContext}，在虚拟线程上用 {@code ScopedValue} 绑定后
 * 再执行回调，所以回调里的租户作用域仓储能读到上下文，见 ADR-0020。
 *
 * <p>执行器为 JDK 的每任务一个虚拟线程执行器，不设上限：并发度由数据库连接池等下游资源约束。
 * 线程安全：无状态，全局共享。
 */
public final class Blocking {

    /** 每个任务一个虚拟线程；线程名带前缀，便于在线程转储中识别。 */
    private static final Scheduler SCHEDULER = Schedulers.fromExecutorService(
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("meridian-blocking-", 0).factory()),
            "meridian-blocking");

    private Blocking() {}

    /**
     * 在虚拟线程上执行有返回值的阻塞调用。
     *
     * @param callable 阻塞调用；返回 {@code null} 时 {@code Mono} 为空
     * @param <T> 结果类型
     * @return 订阅时才执行的 {@code Mono}，在虚拟线程上发出结果或错误
     */
    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.deferContextual(view -> {
                    Optional<PlatformContext> context = PlatformContexts.find(view);
                    return Mono.fromCallable(() -> context.isPresent()
                            ? PlatformContexts.call(context.get(), callable::call)
                            : callable.call());
                })
                .subscribeOn(SCHEDULER);
    }

    /**
     * 在虚拟线程上执行无返回值的阻塞调用。
     *
     * @param runnable 阻塞调用
     * @return 订阅时才执行的 {@code Mono}，调用完成时结束
     */
    public static Mono<Void> run(ThrowingRunnable runnable) {
        return call(() -> {
                    runnable.run();
                    return null;
                })
                .then();
    }

    /** 可以抛出受检异常的无返回值调用。 */
    @FunctionalInterface
    public interface ThrowingRunnable {

        /**
         * 执行调用。
         *
         * @throws Exception 调用失败时，作为 {@code Mono} 的错误传出
         */
        void run() throws Exception;
    }
}
