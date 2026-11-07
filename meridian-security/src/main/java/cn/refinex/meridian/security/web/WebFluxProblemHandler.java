package cn.refinex.meridian.security.web;

import cn.refinex.meridian.common.json.JsonMappers;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务的 problem+json 适配器：作为 {@link WebExceptionHandler}，处理器异常、路由不存在、过滤器异常都经过它，
 * 交给 {@link ProblemMapper}。
 *
 * <p>排在 Spring Boot 默认的错误处理器（order −1）之前。响应已经提交（例如 SSE 流中途出错）时无法再改写，原样传出异常。
 */
public final class WebFluxProblemHandler implements WebExceptionHandler, Ordered {

    /** 排在 Spring Boot 的 {@code DefaultErrorWebExceptionHandler}（−1）之前。 */
    static final int ORDER = -2;

    private final ProblemMapper mapper;

    /**
     * 创建适配器。
     *
     * @param mapper 共用的映射逻辑
     */
    public WebFluxProblemHandler(ProblemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(error);
        }
        Object attribute = exchange.getAttribute(RequestIds.ATTRIBUTE);
        String requestId = attribute instanceof String id ? id : RequestIds.resolve(null);
        Problem problem =
                mapper.map(error, requestId, exchange.getRequest().getPath().value());
        response.setStatusCode(HttpStatusCode.valueOf(problem.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(RequestIds.HEADER, requestId);
        DataBuffer buffer = response.bufferFactory().wrap(JsonMappers.lenient().writeValueAsBytes(problem.body()));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
