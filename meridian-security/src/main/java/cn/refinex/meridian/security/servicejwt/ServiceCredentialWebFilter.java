package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.RequestPaths;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 服务的内部接口过滤器：{@code /internal/} 下的每个请求都要带服务凭据（{@code Authorization: Bearer}），认证后把调用方服务
 * 写入交换属性。认证失败以 {@code auth.*} 错误结束，由 {@code WebFluxProblemHandler} 输出 problem+json。
 *
 * <p>不写 {@code PlatformContext}：内部调用代表哪个租户由请求体声明，处理器据此建立上下文。
 */
public final class ServiceCredentialWebFilter implements WebFilter, Ordered {

    /** 交换属性的键：认证后的 {@link ServicePrincipal}。 */
    public static final String PRINCIPAL_ATTRIBUTE = ServiceCredentialWebFilter.class.getName() + ".principal";

    /** 需要服务凭据的路径的第一段，按 {@link RequestPaths#firstSegment} 比较。 */
    static final String INTERNAL_SEGMENT = "internal";

    /** 与 API Key 过滤器同一位置：在请求 ID 过滤器之后，认证失败的响应也带请求 ID。 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 凭据的长度上限（服务 JWT 也远小于它），超长的头直接拒绝。 */
    static final int MAX_CREDENTIAL_LENGTH = 8_192;

    private static final String BEARER = "bearer ";

    private final ServiceCredentialResolver resolver;

    /**
     * 创建过滤器。
     *
     * @param resolver 服务凭据的来源
     */
    public ServiceCredentialWebFilter(ServiceCredentialResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!INTERNAL_SEGMENT.equals(RequestPaths.firstSegment(exchange.getRequest()))) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return Blocking.call(() -> authenticate(authorization)).flatMap(principal -> {
            exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
            return chain.filter(exchange);
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private ServicePrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
            throw new MeridianException(
                    ServiceCredentialErrorCode.UNAUTHENTICATED, "内部接口需要 Authorization: Bearer <服务凭据>");
        }
        String credential = authorization.substring(BEARER.length()).strip();
        if (credential.isEmpty() || credential.length() > MAX_CREDENTIAL_LENGTH) {
            throw new MeridianException(
                    ServiceCredentialErrorCode.UNAUTHENTICATED, "内部接口需要 Authorization: Bearer <服务凭据>");
        }
        return resolver.resolve(credential)
                .orElseThrow(
                        () -> new MeridianException(ServiceCredentialErrorCode.INVALID_SERVICE_CREDENTIAL, "服务凭据无效"));
    }
}
