package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 平台中间件链的最外层（{@code onAgent}）：校验 {@code PlatformContext}，写入 MDC，打开轮次 span，见架构设计
 * 「平台中间件」「可观测性」。
 *
 * <ul>
 *   <li>校验：{@code PlatformContext} 必须同时在 {@code RuntimeContext}（类型键）与 Reactor Context 中，且是同一个值
 *       （架构设计「身份映射」）；缺失说明调用没有经过平台引擎，以 {@link IllegalStateException} 失败。
 *   <li>MDC：在组装下游的同步阶段写入 {@code tenant_id}、{@code session_id}、{@code turn_id}，结束后恢复原值；跨线程的
 *       MDC 传播属于可观测性的整体方案（T27）。
 *   <li>span：{@code turn}，带平台公共属性，作为 {@code invoke_agent}、{@code chat}、{@code execute_tool} 的父 span；
 *       经 Reactor Context 传给内层，与 AgentScope 的 {@code OtelTracingMiddleware} 读取父 span 的方式一致。
 * </ul>
 *
 * <p>线程安全：无状态，可被所有智能体实例共享。
 */
public final class PlatformContextMiddleware implements MiddlewareBase {

    /** 中间件顺序：平台中间件中最外层。数值大的在外层，取值见模块 {@code CLAUDE.md}。 */
    public static final int ORDER = 300;

    /** 轮次 span 的名字，架构设计「可观测性」中 span 层级的第一层。 */
    static final String TURN_SPAN = "turn";

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            PlatformContext platform = ctx == null ? null : ctx.get(PlatformContext.class);
            Optional<PlatformContext> reactive = PlatformContexts.find(view);
            if (platform == null || reactive.isEmpty() || !platform.equals(reactive.get())) {
                return Flux.error(new IllegalStateException(
                        "调用没有经过平台引擎：RuntimeContext 与 Reactor Context 中必须是同一个 PlatformContext"));
            }
            Context parent = Tracing.otelContext(view);
            Span span = Tracing.tracer()
                    .spanBuilder(TURN_SPAN)
                    .setParent(parent)
                    .setAllAttributes(Tracing.platformAttributes(platform))
                    .startSpan();
            Flux<AgentEvent> inner;
            try (MdcScope ignored = new MdcScope(platform)) {
                inner = next.apply(input);
            }
            return ContextPropagationOperator.runWithContext(inner, span.storeInContext(parent))
                    .doOnError(error -> {
                        span.recordException(error);
                        span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                    })
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            span.setStatus(StatusCode.ERROR, "cancelled");
                        }
                        span.end();
                    });
        });
    }

    /** 在作用域内写入 MDC 的平台键，关闭时恢复原值。 */
    private static final class MdcScope implements AutoCloseable {

        private static final String TENANT_ID = "tenant_id";
        private static final String SESSION_ID = "session_id";
        private static final String TURN_ID = "turn_id";

        private final String previousTenant = MDC.get(TENANT_ID);
        private final String previousSession = MDC.get(SESSION_ID);
        private final String previousTurn = MDC.get(TURN_ID);

        MdcScope(PlatformContext platform) {
            put(TENANT_ID, platform.tenantId());
            put(SESSION_ID, platform.sessionId());
            put(TURN_ID, platform.turnId());
        }

        @Override
        public void close() {
            put(TENANT_ID, previousTenant);
            put(SESSION_ID, previousSession);
            put(TURN_ID, previousTurn);
        }

        private static void put(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
