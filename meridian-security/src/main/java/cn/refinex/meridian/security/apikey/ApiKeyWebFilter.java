package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务（runtime）的 API Key 过滤器：公开 API（{@code /v1/} 下的路径）的每个请求都要认证，认证后把主体写入交换属性、
 * 把 {@code PlatformContext}（租户、项目、API Key）写入 Reactor Context（架构设计「身份映射」「认证」）。
 *
 * <p>认证失败以 {@code auth.*} 错误结束，由 {@code WebFluxProblemHandler} 输出 problem+json。其他路径（健康检查等）不经过认证。
 */
public final class ApiKeyWebFilter implements WebFilter, Ordered {

    /** 交换属性的键：认证后的 {@link ApiKeyPrincipal}，作用域检查从这里读取。 */
    public static final String PRINCIPAL_ATTRIBUTE = ApiKeyWebFilter.class.getName() + ".principal";

    /** 需要认证的路径前缀：公开 API 的版本前缀（架构设计「约定」）。 */
    static final String API_PREFIX = "/v1/";

    /** 在请求 ID 过滤器之后执行，认证失败的响应也带请求 ID。 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final ApiKeyAuthenticator authenticator;

    /**
     * 创建过滤器。
     *
     * @param authenticator API Key 认证器
     */
    public ApiKeyWebFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith(API_PREFIX)) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        // 解析器可能查询外部来源（T14a），经 Blocking 调度，不阻塞事件循环
        return Blocking.call(() -> authenticator.authenticate(authorization)).flatMap(principal -> {
            exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
            return chain.filter(exchange).contextWrite(PlatformContexts.write(principal.toContext()));
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
