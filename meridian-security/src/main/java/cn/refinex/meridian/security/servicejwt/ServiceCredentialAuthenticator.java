package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.BearerTokens;

/**
 * 内部接口的服务凭据认证：解析 {@code Authorization: Bearer}，交给 {@link ServiceCredentialResolver}。WebFlux 与 MVC 的过滤器共用。
 *
 * <p>方法可能阻塞（取决于解析器），WebFlux 中经 {@code Blocking} 调用。线程安全：无状态。
 */
public final class ServiceCredentialAuthenticator {

    /** 凭据的长度上限（服务 JWT 也远小于它），超长的头直接拒绝。 */
    static final int MAX_CREDENTIAL_LENGTH = 8_192;

    private final ServiceCredentialResolver resolver;

    /**
     * 创建认证器。
     *
     * @param resolver 服务凭据的来源
     */
    public ServiceCredentialAuthenticator(ServiceCredentialResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 认证一个内部请求。
     *
     * @param authorization {@code Authorization} 请求头的值，可为空
     * @return 调用方服务
     * @throws MeridianException 缺少或格式不对时为 {@code auth.unauthenticated}；凭据无效时为 {@code auth.invalid_service_credential}
     */
    public ServicePrincipal authenticate(String authorization) {
        String credential = BearerTokens.parse(authorization, MAX_CREDENTIAL_LENGTH)
                .orElseThrow(() -> new MeridianException(
                        ServiceCredentialErrorCode.UNAUTHENTICATED, "内部接口需要 Authorization: Bearer <服务凭据>"));
        return resolver.resolve(credential)
                .orElseThrow(
                        () -> new MeridianException(ServiceCredentialErrorCode.INVALID_SERVICE_CREDENTIAL, "服务凭据无效"));
    }
}
