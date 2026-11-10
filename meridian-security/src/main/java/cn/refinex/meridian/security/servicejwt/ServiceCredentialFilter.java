package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.PrefixAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * MVC 服务（control-plane、knowledge）的内部接口过滤器：{@code /internal/} 下的每个请求都要带服务 JWT，认证后把调用方服务写入请求
 * 属性。与 WebFlux 的 {@link ServiceCredentialWebFilter} 共用 {@link ServiceCredentialAuthenticator}；不写 {@code PlatformContext}，
 * 处理器按请求声明的租户建立上下文。
 */
public final class ServiceCredentialFilter extends PrefixAuthenticationFilter<ServicePrincipal> {

    /** 请求属性的键：认证后的 {@link ServicePrincipal}。 */
    public static final String PRINCIPAL_ATTRIBUTE = ServiceCredentialFilter.class.getName() + ".principal";

    private final ServiceCredentialAuthenticator authenticator;

    /**
     * 创建过滤器。
     *
     * @param authenticator 服务凭据认证器
     * @param errors Spring MVC 的异常解析器，输出认证失败的 problem+json
     */
    public ServiceCredentialFilter(ServiceCredentialAuthenticator authenticator, HandlerExceptionResolver errors) {
        super(ServiceCredentialWebFilter.INTERNAL_SEGMENT, PRINCIPAL_ATTRIBUTE, errors);
        this.authenticator = authenticator;
    }

    /**
     * 内部接口处理器的认证与调用方检查（MVC 版 {@link ServiceCredentialAuthorization#require}）：过滤器之外的第二道保护，失败即关闭。
     *
     * @param request 当前请求
     * @param caller 允许的调用方服务名
     * @param otherCallers 其他允许的调用方服务名
     * @return 调用方服务
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}）
     * @throws cn.refinex.meridian.common.error.ForbiddenException 调用方不在允许之列时（{@code access.forbidden}）
     */
    public static ServicePrincipal require(HttpServletRequest request, String caller, String... otherCallers) {
        ServicePrincipal principal =
                request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof ServicePrincipal found ? found : null;
        return ServiceCredentialAuthorization.check(principal, caller, otherCallers);
    }

    @Override
    protected ServicePrincipal authenticate(String authorization) {
        return authenticator.authenticate(authorization);
    }
}
