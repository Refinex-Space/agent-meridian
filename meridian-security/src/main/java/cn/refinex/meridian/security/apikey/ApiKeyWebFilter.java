package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.RequestPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务（runtime）的 API Key 过滤器：公开 API（{@code /v1/} 下的路径）的每个请求都要认证，认证后按 Key 限流，再把主体写入
 * 交换属性、把 {@code PlatformContext}（租户、项目、API Key）写入 Reactor Context（架构设计「身份映射」「认证」「约定」）。
 *
 * <p>限流（ADR-0042）：主体带每分钟上限且应用有 {@link ApiKeyRateLimiter} 时，每个请求消耗一个令牌，响应带 {@code RateLimit-Limit}、
 * {@code RateLimit-Remaining}、{@code RateLimit-Reset}；超限以 429 {@code rate_limit.exceeded} 结束并带 {@code Retry-After}。限流器
 * 出错时放行并记 WARN：限流的故障不扩大为 API 不可用。
 *
 * <p>认证失败以 {@code auth.*} 错误结束，由 {@code WebFluxProblemHandler} 输出 problem+json。其他路径（健康检查等）不经过认证。
 */
public final class ApiKeyWebFilter implements WebFilter, Ordered {

    /** 交换属性的键：认证后的 {@link ApiKeyPrincipal}，作用域检查从这里读取。 */
    public static final String PRINCIPAL_ATTRIBUTE = ApiKeyWebFilter.class.getName() + ".principal";

    /** 响应头：上限。 */
    public static final String LIMIT_HEADER = "RateLimit-Limit";

    /** 响应头：剩余请求数。 */
    public static final String REMAINING_HEADER = "RateLimit-Remaining";

    /** 响应头：多少秒后补满。 */
    public static final String RESET_HEADER = "RateLimit-Reset";

    /** 需要认证的路径的第一段：公开 API 的版本前缀（架构设计「约定」），按 {@link RequestPaths#firstSegment} 比较。 */
    static final String API_SEGMENT = "v1";

    /** 在请求 ID 过滤器之后执行，认证失败的响应也带请求 ID。 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyWebFilter.class);

    private final ApiKeyAuthenticator authenticator;
    private final ApiKeyRateLimiter rateLimiter;

    /**
     * 创建不限流的过滤器。
     *
     * @param authenticator API Key 认证器
     */
    public ApiKeyWebFilter(ApiKeyAuthenticator authenticator) {
        this(authenticator, null);
    }

    /**
     * 创建过滤器。
     *
     * @param authenticator API Key 认证器
     * @param rateLimiter 限流器；可为空，表示不限流
     */
    public ApiKeyWebFilter(ApiKeyAuthenticator authenticator, ApiKeyRateLimiter rateLimiter) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!API_SEGMENT.equals(RequestPaths.firstSegment(exchange.getRequest()))) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        // 解析器查询 control-plane、限流器访问 Redis，都经 Blocking 调度，不阻塞事件循环
        return Blocking.call(() -> {
                    ApiKeyPrincipal principal = authenticator.authenticate(authorization);
                    return new Admitted(principal, limit(principal));
                })
                .flatMap(admitted -> {
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    RateLimitDecision decision = admitted.decision();
                    if (decision != null) {
                        headers.set(LIMIT_HEADER, Integer.toString(decision.limit()));
                        headers.set(REMAINING_HEADER, Long.toString(decision.remaining()));
                        headers.set(RESET_HEADER, Long.toString(decision.resetSeconds()));
                        if (!decision.allowed()) {
                            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
                            return Mono.error(new MeridianException(
                                    ApiKeyErrorCode.RATE_LIMITED,
                                    "API Key 的请求速率超过每分钟 " + decision.limit() + " 次，请 " + decision.retryAfterSeconds()
                                            + " 秒后重试"));
                        }
                    }
                    exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, admitted.principal());
                    return chain.filter(exchange)
                            .contextWrite(
                                    PlatformContexts.write(admitted.principal().toContext()));
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private RateLimitDecision limit(ApiKeyPrincipal principal) {
        if (rateLimiter == null || principal.rateLimitPerMinute() == null) {
            return null;
        }
        try {
            return rateLimiter.consume(principal.apiKeyId(), principal.rateLimitPerMinute());
        } catch (RuntimeException e) {
            log.warn("API Key 限流失败，本次放行 apiKeyId={}", principal.apiKeyId(), e);
            return null;
        }
    }

    /**
     * 认证与限流的结果。
     *
     * @param principal 主体
     * @param decision 限流判定；不限流时为空
     */
    private record Admitted(ApiKeyPrincipal principal, RateLimitDecision decision) {}
}
