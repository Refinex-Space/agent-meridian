package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.MeridianException;
import org.springframework.web.server.ServerWebExchange;

/**
 * 内部接口处理器的认证检查，与公开 API 的 {@code ApiKeyAuthorization.require} 对应：过滤器之外的第二道保护，
 * 路径判断出错或新增处理器挂在过滤器范围之外时失败即关闭（R1 安全审查，ADR-0041）。
 */
public final class ServiceCredentialAuthorization {

    private ServiceCredentialAuthorization() {}

    /**
     * 要求当前请求已经过服务凭据认证。
     *
     * @param exchange 当前请求
     * @return 调用方服务
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}）
     */
    public static ServicePrincipal require(ServerWebExchange exchange) {
        ServicePrincipal principal = exchange.getAttribute(ServiceCredentialWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new MeridianException(ServiceCredentialErrorCode.UNAUTHENTICATED, "内部接口需要服务凭据");
        }
        return principal;
    }
}
