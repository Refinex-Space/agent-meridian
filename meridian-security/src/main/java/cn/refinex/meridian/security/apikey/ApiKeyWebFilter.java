package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.RequestPaths;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务（runtime）的公开 API 认证过滤器：{@code /v1/} 下的每个请求都要认证，认证后限流，再把主体写入交换属性、把
 * {@code PlatformContext}（租户、项目、API Key 或控制台用户）写入 Reactor Context（架构设计「身份映射」「认证」「约定」）。
 *
 * <p>认证与限流的规则在 {@link PublicApiAdmission}，与 MVC 服务的 {@link ApiKeyFilter} 相同：按令牌形状分流 API Key 与控制台用户的
 * OIDC 令牌（ADR-0044）；超限以 429 {@code rate_limit.exceeded} 结束并带 {@code Retry-After}（ADR-0042）。
 *
 * <p>认证失败以 {@code auth.*} 错误结束，由 {@code WebFluxProblemHandler} 输出 problem+json。其他路径（健康检查等）不经过认证。
 */
public final class ApiKeyWebFilter implements WebFilter, Ordered {

    /** 交换属性的键：认证后的 {@link PublicApiPrincipal}，作用域检查从这里读取。 */
    public static final String PRINCIPAL_ATTRIBUTE = ApiKeyWebFilter.class.getName() + ".principal";

    /** 响应头：上限。 */
    public static final String LIMIT_HEADER = PublicApiAdmission.LIMIT_HEADER;

    /** 响应头：剩余请求数。 */
    public static final String REMAINING_HEADER = PublicApiAdmission.REMAINING_HEADER;

    /** 响应头：多少秒后补满。 */
    public static final String RESET_HEADER = PublicApiAdmission.RESET_HEADER;

    /** 需要认证的路径的第一段：公开 API 的版本前缀（架构设计「约定」），按 {@link RequestPaths#firstSegment} 比较。 */
    static final String API_SEGMENT = PublicApiAdmission.API_SEGMENT;

    /** 控制台令牌的长度上限：OIDC access token 通常在 2 KB 以内，与管理 API 的上限相同。 */
    static final int MAX_CONSOLE_TOKEN_LENGTH = PublicApiAdmission.MAX_CONSOLE_TOKEN_LENGTH;

    /** 在请求 ID 过滤器之后执行，认证失败的响应也带请求 ID。 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 控制台用户指定项目的请求头（架构设计「认证」中"控制台用户"一行）。 */
    public static final String PROJECT_HEADER = PublicApiAdmission.PROJECT_HEADER;

    private final PublicApiAdmission admission;

    /**
     * 创建只接受 API Key、不限流的过滤器。
     *
     * @param authenticator API Key 认证器
     */
    public ApiKeyWebFilter(ApiKeyAuthenticator authenticator) {
        this(authenticator, null, null);
    }

    /**
     * 创建过滤器。
     *
     * @param authenticator API Key 认证器
     * @param consoleAuthenticator 控制台用户令牌的认证；可为空，表示只接受 API Key
     * @param rateLimiter 限流器；可为空，表示不限流
     */
    public ApiKeyWebFilter(
            ApiKeyAuthenticator authenticator,
            ConsoleTokenAuthenticator consoleAuthenticator,
            ApiKeyRateLimiter rateLimiter) {
        this.admission = new PublicApiAdmission(authenticator, consoleAuthenticator, rateLimiter);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!API_SEGMENT.equals(RequestPaths.firstSegment(exchange.getRequest()))) {
            return chain.filter(exchange);
        }
        HttpHeaders requestHeaders = exchange.getRequest().getHeaders();
        String authorization = requestHeaders.getFirst(HttpHeaders.AUTHORIZATION);
        String project = requestHeaders.getFirst(PROJECT_HEADER);
        // 解析器查询 control-plane、校验令牌、限流器访问 Redis，都经 Blocking 调度，不阻塞事件循环
        return Blocking.call(() -> admission.admit(authorization, project)).flatMap(admitted -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            Optional<MeridianException> limited = admitted.applyTo(headers::set);
            if (limited.isPresent()) {
                return Mono.error(limited.get());
            }
            exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, admitted.principal());
            return chain.filter(exchange)
                    .contextWrite(PlatformContexts.write(admitted.principal().toContext()));
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
