package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 每个工具调用一个 span（{@code onActing}）：AgentScope 的 {@code OtelTracingMiddleware} 一批工具只建一个
 * {@code execute_tool} span，这里在它之内为每个调用建 {@code execute_tool {name}}，带调用 ID、结果状态与平台公共属性，
 * 见架构设计「平台中间件」「可观测性」。
 *
 * <p>span 从 {@code TOOL_RESULT_START} 开始、到 {@code TOOL_RESULT_END} 结束。2.0.4 在一批工具全部执行完之后才逐个发出
 * {@code TOOL_RESULT_END}，所以并行工具的 span 结束时间接近整批的结束时间；单个工具的精确耗时由平台工具基类记录（T10a）。
 * 被拒绝的调用（DENIED）同样有 span，状态不算错误；批次被取消或出错时，未结束的 span 以错误结束。
 *
 * <p>线程安全：无状态；每次 {@code onActing} 的 span 表只属于该次调用。
 */
public final class ToolSpanMiddleware implements MiddlewareBase {

    /** 中间件顺序：平台中间件中最内层，位于 AgentScope 的批次 span 之内。 */
    public static final int ORDER = 50;

    /** 工具结果状态的 span 属性。 */
    static final AttributeKey<String> TOOL_STATE = AttributeKey.stringKey("meridian.tool.state");

    static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Context parent = Tracing.otelContext(view);
            Attributes platform = Tracing.platformAttributes(ctx);
            Map<String, Span> spans = new ConcurrentHashMap<>();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultStartEvent start && start.getToolCallId() != null) {
                            spans.put(
                                    start.getToolCallId(),
                                    Tracing.tracer()
                                            .spanBuilder("execute_tool " + start.getToolCallName())
                                            .setParent(parent)
                                            .setAllAttributes(platform)
                                            .setAttribute(OPERATION, "execute_tool")
                                            .setAttribute(TOOL_NAME, start.getToolCallName())
                                            .setAttribute(TOOL_CALL_ID, start.getToolCallId())
                                            .startSpan());
                        } else if (event instanceof ToolResultEndEvent end && end.getToolCallId() != null) {
                            Span span = spans.remove(end.getToolCallId());
                            if (span != null) {
                                ToolResultState state = end.getState();
                                span.setAttribute(TOOL_STATE, state == null ? "unknown" : state.getValue());
                                if (state == ToolResultState.ERROR || state == ToolResultState.INTERRUPTED) {
                                    span.setStatus(StatusCode.ERROR, state.getValue());
                                }
                                span.end();
                            }
                        }
                    })
                    .doFinally(signal -> spans.values().forEach(span -> {
                        span.setStatus(StatusCode.ERROR, signal.toString());
                        span.end();
                    }));
        });
    }
}
