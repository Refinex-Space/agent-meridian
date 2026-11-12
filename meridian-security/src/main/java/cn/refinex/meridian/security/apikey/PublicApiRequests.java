package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * MVC 服务公开 API 处理器的作用域检查，与 WebFlux 的 {@link ApiKeyAuthorization} 相同：处理器先调它，再调用用例（D55）。
 */
public final class PublicApiRequests {

    private PublicApiRequests() {}

    /**
     * 要求当前请求的主体（API Key，或带 {@code Meridian-Project} 的控制台用户）有某个作用域。
     *
     * @param request 当前请求
     * @param scope 所需作用域
     * @return 认证后的主体
     * @throws ForbiddenException 缺少作用域时（{@code auth.insufficient_scope}）
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}），说明路径不在认证范围内
     */
    public static PublicApiPrincipal require(HttpServletRequest request, ApiKeyScope scope) {
        if (!(request.getAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE) instanceof PublicApiPrincipal principal)) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求没有经过认证");
        }
        if (!principal.hasScope(scope)) {
            throw new ForbiddenException(ApiKeyErrorCode.INSUFFICIENT_SCOPE, "缺少作用域 " + scope.value() + "，无法执行该操作");
        }
        return principal;
    }
}
