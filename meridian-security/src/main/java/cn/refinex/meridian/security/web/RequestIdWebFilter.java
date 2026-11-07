package cn.refinex.meridian.security.web;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务（runtime）的请求 ID 过滤器：读取或生成 {@code X-Request-Id}，写入交换属性、响应头与 Reactor Context。
 *
 * <p>WebFlux 的一个请求会在多个线程间切换，不能直接写 MDC：请求 ID 写进 Reactor Context 的 {@code request_id} 键，
 * 由 {@link RequestIdMdcAccessor} 借助 Reactor 的自动上下文传播在每个算子执行时恢复到 MDC。
 */
public final class RequestIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = RequestIds.resolve(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        exchange.getAttributes().put(RequestIds.ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(RequestIds.HEADER, requestId);
        return chain.filter(exchange).contextWrite(context -> context.put(RequestIds.MDC_KEY, requestId));
    }

    /** 最高优先级：在认证等所有过滤器之前执行。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
