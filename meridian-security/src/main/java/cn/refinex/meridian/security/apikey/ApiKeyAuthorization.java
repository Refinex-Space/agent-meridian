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
     * 要求当前请求的 API Key 有某个作用域。
     *
     * @param exchange 当前请求
     * @param scope 所需作用域
     * @return 认证后的主体
     * @throws ForbiddenException 缺少作用域时（{@code auth.insufficient_scope}）
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}），说明路径不在认证范围内
     */
    public static ApiKeyPrincipal require(ServerWebExchange exchange, ApiKeyScope scope) {
        ApiKeyPrincipal principal = exchange.getAttribute(ApiKeyWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求没有经过认证");
        }
        if (!principal.hasScope(scope)) {
            throw new ForbiddenException(
                    ApiKeyErrorCode.INSUFFICIENT_SCOPE, "API Key 缺少作用域 " + scope.value() + "，无法执行该操作");
        }
        return principal;
    }
}
