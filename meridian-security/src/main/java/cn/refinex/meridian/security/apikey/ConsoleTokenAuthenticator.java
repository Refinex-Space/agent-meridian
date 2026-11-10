package cn.refinex.meridian.security.apikey;

/**
 * 公开 API 上控制台用户令牌的认证（ADR-0044）：OIDC access token 加 {@code Meridian-Project} 头。由 {@code oidc} 包实现，公开 API 的
 * 过滤器按令牌形状分流到这里；放在本包是为了不让 {@code apikey} 依赖 {@code oidc}。
 *
 * <p>方法可能阻塞（校验令牌、查询项目权限），WebFlux 中经 {@code Blocking} 调用。
 */
@FunctionalInterface
public interface ConsoleTokenAuthenticator {

    /**
     * 认证一个控制台用户的请求。
     *
     * @param token {@code Bearer} 中的令牌，形状是 JWT
     * @param project {@code Meridian-Project} 请求头的值；可为空
     * @return 主体
     * @throws cn.refinex.meridian.common.error.MeridianException 令牌无效（401）、缺少项目（400）、没有该项目的作用域（403）时
     */
    PublicApiPrincipal authenticate(String token, String project);
}
