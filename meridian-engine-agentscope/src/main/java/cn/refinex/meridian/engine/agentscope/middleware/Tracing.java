package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.common.context.PlatformContext;
import io.agentscope.core.agent.RuntimeContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.util.context.ContextView;

/**
 * 平台中间件共用的追踪工具：tracer、从 Reactor Context 取 OpenTelemetry 上下文、平台公共属性。
 *
 * <p>OpenTelemetry 上下文在 Reactor Context 中传递，与 AgentScope 的 {@code OtelTracingMiddleware} 一致；线程切换后
 * {@link Context#current()} 不可靠，所以先读 Reactor Context。
 */
final class Tracing {

    /** 平台 span 的 instrumentation 名。 */
    static final String INSTRUMENTATION_NAME = "cn.refinex.meridian.engine";

    /** 架构设计「可观测性」规定每个 span 都带的公共属性。 */
    static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("meridian.tenant_id");

    static final AttributeKey<String> PROJECT_ID = AttributeKey.stringKey("meridian.project_id");
    static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("meridian.session_id");
    static final AttributeKey<String> THREAD_ID = AttributeKey.stringKey("meridian.thread_id");
    static final AttributeKey<String> TURN_ID = AttributeKey.stringKey("meridian.turn_id");

    private Tracing() {}

    static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    static Context otelContext(ContextView view) {
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(view, Context.current());
    }

    /**
     * 平台公共属性；没有 {@code PlatformContext} 时为空（不是经平台引擎执行的调用）。
     *
     * @param ctx 本次调用的上下文
     * @return 属性
     */
    static Attributes platformAttributes(RuntimeContext ctx) {
        PlatformContext platform = ctx == null ? null : ctx.get(PlatformContext.class);
        return platform == null ? Attributes.empty() : platformAttributes(platform);
    }

    static Attributes platformAttributes(PlatformContext platform) {
        AttributesBuilder attributes = Attributes.builder().put(TENANT_ID, platform.tenantId());
        putIfPresent(attributes, PROJECT_ID, platform.projectId());
        putIfPresent(attributes, SESSION_ID, platform.sessionId());
        putIfPresent(attributes, THREAD_ID, platform.threadId());
        putIfPresent(attributes, TURN_ID, platform.turnId());
        return attributes.build();
    }

    private static void putIfPresent(AttributesBuilder attributes, AttributeKey<String> key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
