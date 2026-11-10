package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import java.util.List;
import org.springframework.web.server.ServerWebExchange;

/**
 * 内部接口处理器的认证与调用方检查，与公开 API 的 {@code ApiKeyAuthorization.require} 对应：过滤器之外的第二道保护，路径判断出错或
 * 新增处理器挂在过滤器范围之外时失败即关闭（R1 安全审查，ADR-0041）；每个内部接口只接受列明的调用方服务（ADR-0044）。
 */
public final class ServiceCredentialAuthorization {

    private ServiceCredentialAuthorization() {}

    /**
     * 要求当前请求已经过服务 JWT 认证，且调用方是允许的服务之一。
     *
     * @param exchange 当前请求
     * @param caller 允许的调用方服务名
     * @param otherCallers 其他允许的调用方服务名
     * @return 调用方服务
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}）
     * @throws ForbiddenException 调用方不在允许之列时（{@code access.forbidden}）
     */
    public static ServicePrincipal require(ServerWebExchange exchange, String caller, String... otherCallers) {
        ServicePrincipal principal = exchange.getAttribute(ServiceCredentialWebFilter.PRINCIPAL_ATTRIBUTE);
        return check(principal, caller, otherCallers);
    }

    /**
     * 检查主体与调用方，两种 Web 栈共用。
     *
     * @param principal 认证后的主体；可为空，表示没有经过认证
     * @param caller 允许的调用方服务名
     * @param otherCallers 其他允许的调用方服务名
     * @return 调用方服务
     */
    static ServicePrincipal check(ServicePrincipal principal, String caller, String... otherCallers) {
        if (principal == null) {
            throw new MeridianException(ServiceCredentialErrorCode.UNAUTHENTICATED, "内部接口需要服务凭据");
        }
        if (!principal.service().equals(caller) && !List.of(otherCallers).contains(principal.service())) {
            throw new ForbiddenException(CommonErrorCode.FORBIDDEN, "服务 " + principal.service() + " 不能调用本接口");
        }
        return principal;
    }
}
