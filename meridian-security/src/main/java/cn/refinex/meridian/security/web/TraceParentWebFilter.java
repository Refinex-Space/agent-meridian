package cn.refinex.meridian.security.web;

import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务的 {@code traceparent} 过滤器：合法的传入值在响应头中原样返回，trace-id 写入 Reactor Context 的 {@code trace_id}，
 * 由 {@link TraceIdMdcAccessor} 恢复到日志 MDC（见 {@link TraceParents}）。
 */
public final class TraceParentWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(TraceParents.HEADER);
        Optional<String> traceId = TraceParents.traceId(header);
        if (traceId.isEmpty()) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set(TraceParents.HEADER, header);
        return chain.filter(exchange).contextWrite(context -> context.put(TraceParents.MDC_KEY, traceId.get()));
    }

    /** 紧跟请求 ID 过滤器，在认证之前执行，认证失败的日志也能关联到调用方的追踪。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
