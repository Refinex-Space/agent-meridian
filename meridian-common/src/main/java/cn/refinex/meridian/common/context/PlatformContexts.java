package cn.refinex.meridian.common.context;

import java.util.Optional;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * {@link PlatformContext} 的绑定与读取（编码规范「并发与响应式」）。
 *
 * <ul>
 *   <li>阻塞代码（MVC 服务、虚拟线程上的 JDBC）用 {@link ScopedValue}：{@link #run}、{@link #call} 绑定，
 *       {@link #current()} 读取。绑定只在回调执行期间有效，回调返回后自动解除，不会像 {@code ThreadLocal}
 *       那样泄漏到复用的线程上。
 *   <li>响应式代码（runtime）用 Reactor Context，键为 {@code PlatformContext.class}（架构设计「身份映射」）：
 *       {@link #write} 写入，{@link #find(ContextView)}、{@link #currentReactive()} 读取。
 * </ul>
 *
 * <p>两者之间的桥接由 {@code common.concurrent.Blocking} 完成：它把 Reactor Context 中的上下文绑定到
 * 执行阻塞调用的虚拟线程上。其他地方跨越两者时显式传递，不用 {@code ThreadLocal}。
 */
public final class PlatformContexts {

    private static final ScopedValue<PlatformContext> CURRENT = ScopedValue.newInstance();

    /** Reactor Context 中的键，与架构设计「身份映射」的"类型键"一致。 */
    private static final Class<PlatformContext> REACTOR_KEY = PlatformContext.class;

    private PlatformContexts() {}

    /**
     * 在绑定了上下文的作用域内执行操作。
     *
     * @param context 要绑定的上下文
     * @param operation 要执行的操作
     */
    public static void run(PlatformContext context, Runnable operation) {
        ScopedValue.where(CURRENT, context).run(operation);
    }

    /**
     * 在绑定了上下文的作用域内执行操作并返回结果。
     *
     * @param context 要绑定的上下文
     * @param operation 要执行的操作
     * @param <R> 结果类型
     * @param <X> 操作可能抛出的异常类型
     * @return 操作的结果
     * @throws X 操作抛出的异常，原样传出
     */
    public static <R, X extends Throwable> R call(PlatformContext context, ScopedValue.CallableOp<R, X> operation)
            throws X {
        return ScopedValue.where(CURRENT, context).call(operation);
    }

    /**
     * 读取当前作用域绑定的上下文。
     *
     * @return 当前上下文
     * @throws IllegalStateException 当前作用域没有绑定上下文时。这是编程错误：入口没有填充上下文，
     *     或在响应式代码中没有经 {@code Blocking} 就执行了阻塞调用
     */
    public static PlatformContext current() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("当前作用域没有绑定 PlatformContext");
        }
        return CURRENT.get();
    }

    /**
     * 读取当前作用域绑定的上下文，不存在时返回空。
     *
     * @return 当前上下文
     */
    public static Optional<PlatformContext> find() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /**
     * 向 Reactor Context 写入上下文，用法：{@code mono.contextWrite(PlatformContexts.write(ctx))}。
     *
     * @param context 要写入的上下文
     * @return 供 {@code contextWrite} 使用的函数
     */
    public static Function<Context, Context> write(PlatformContext context) {
        return reactorContext -> reactorContext.put(REACTOR_KEY, context);
    }

    /**
     * 从 Reactor Context 读取上下文。
     *
     * @param view Reactor Context
     * @return 上下文，不存在时为空
     */
    public static Optional<PlatformContext> find(ContextView view) {
        return view.getOrEmpty(REACTOR_KEY);
    }

    /**
     * 从订阅方的 Reactor Context 读取上下文。
     *
     * @return 发出上下文的 {@code Mono}；不存在时以 {@link IllegalStateException} 结束
     */
    public static Mono<PlatformContext> currentReactive() {
        return Mono.deferContextual(view -> find(view)
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new IllegalStateException("Reactor Context 中没有 PlatformContext"))));
    }
}
