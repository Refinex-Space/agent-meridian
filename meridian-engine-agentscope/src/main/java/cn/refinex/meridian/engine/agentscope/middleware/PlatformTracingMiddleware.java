package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.trace.Span;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 装配 AgentScope 自带的 {@link OtelTracingMiddleware}（GenAI 语义约定的 {@code invoke_agent}、{@code chat {model}}、
 * {@code execute_tool} span），并给这些 span 补上平台公共属性，见架构设计「平台中间件」「可观测性」。
 *
 * <p>采用委托而不是继承：{@code OtelTracingMiddleware} 的 span 在它自己的方法里创建，属性只能在 span 已放进
 * Reactor Context 之后由内层补上。这里把 {@code next} 包一层，订阅时从 Reactor Context 取出当前 span 写入属性；
 * 同时给它一个平台定义的 {@code order()}，让它位于轮次 span 之内、租约守卫与工具 span 之外。
 *
 * <p>{@code OtelTracingMiddleware} 的构造函数会全局注册一次 Reactor 的 OpenTelemetry 上下文传播钩子（AgentScope 的行为）。
 * 提示词与模型输出不进入追踪：AgentScope 2.0.4 的这些 span 只带模型名、消息数、工具名与用量。
 *
 * <p>线程安全：无状态，可被所有智能体实例共享。
 */
public final class PlatformTracingMiddleware implements MiddlewareBase {

    /** 中间件顺序：在轮次 span 之内、租约守卫之外。 */
    public static final int ORDER = 200;

    private final OtelTracingMiddleware delegate = new OtelTracingMiddleware();

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        return delegate.onAgent(agent, ctx, input, withPlatformAttributes(ctx, next));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return delegate.onModelCall(agent, ctx, input, withPlatformAttributes(ctx, next));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return delegate.onActing(agent, ctx, input, withPlatformAttributes(ctx, next));
    }

    /** 包装 {@code next}：订阅时给 {@code OtelTracingMiddleware} 刚创建、放在 Reactor Context 中的 span 写入平台属性。 */
    private static <I> Function<I, Flux<AgentEvent>> withPlatformAttributes(
            RuntimeContext ctx, Function<I, Flux<AgentEvent>> next) {
        return in -> Flux.deferContextual(view -> {
            Span.fromContext(Tracing.otelContext(view)).setAllAttributes(Tracing.platformAttributes(ctx));
            return next.apply(in);
        });
    }
}
