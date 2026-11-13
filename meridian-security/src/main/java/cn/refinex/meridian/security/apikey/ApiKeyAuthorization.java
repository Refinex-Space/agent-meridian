package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import org.springframework.web.server.ServerWebExchange;

/**
 * 作用域检查（架构设计「授权」）：处理器在执行操作前调用，API Key 缺少该操作的作用域时拒绝。
 */
public final class ApiKeyAuthorization {

    private ApiKeyAuthorization() {}

    /**
     * 要求当前请求的主体（API Key，或带 {@code Meridian-Project} 的控制台用户）有某个作用域。
     *
     * @param exchange 当前请求
     * @param scope 所需作用域
     * @return 认证后的主体
     * @throws ForbiddenException 缺少作用域时（{@code auth.insufficient_scope}）
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}），说明路径不在认证范围内
     */
    public static PublicApiPrincipal require(ServerWebExchange exchange, ApiKeyScope scope) {
        PublicApiPrincipal principal = exchange.getAttribute(ApiKeyWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求没有经过认证");
        }
        if (!principal.hasScope(scope)) {
            throw new ForbiddenException(ApiKeyErrorCode.INSUFFICIENT_SCOPE, "缺少作用域 " + scope.value() + "，无法执行该操作");
        }
        return principal;
    }

    /**
     * 要求当前请求的主体至少有其中一个作用域；之后由处理器按请求内容再用 {@link #require(PublicApiPrincipal, ApiKeyScope)} 收窄
     * （例如只有 {@code actions:respond} 时只能发送回应事件，ADR-0053）。
     *
     * @param exchange 当前请求
     * @param scopes 可选的作用域，至少一个
     * @return 认证后的主体
     * @throws ForbiddenException 一个都没有时（{@code auth.insufficient_scope}）
     * @throws MeridianException 请求没有经过认证时
     */
    public static PublicApiPrincipal requireAny(ServerWebExchange exchange, ApiKeyScope... scopes) {
        PublicApiPrincipal principal = exchange.getAttribute(ApiKeyWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求没有经过认证");
        }
        for (ApiKeyScope scope : scopes) {
            if (principal.hasScope(scope)) {
                return principal;
            }
        }
        throw new ForbiddenException(
                ApiKeyErrorCode.INSUFFICIENT_SCOPE,
                "缺少作用域 "
                        + java.util.Arrays.stream(scopes)
                                .map(ApiKeyScope::value)
                                .toList() + " 之一，无法执行该操作");
    }

    /**
     * 要求已认证的主体有某个作用域。
     *
     * @param principal 主体
     * @param scope 所需作用域
     * @throws ForbiddenException 缺少作用域时（{@code auth.insufficient_scope}）
     */
    public static void require(PublicApiPrincipal principal, ApiKeyScope scope) {
        if (!principal.hasScope(scope)) {
            throw new ForbiddenException(ApiKeyErrorCode.INSUFFICIENT_SCOPE, "缺少作用域 " + scope.value() + "，无法执行该操作");
        }
    }
}
