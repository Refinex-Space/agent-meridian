package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.BearerTokens;
import cn.refinex.meridian.security.web.PrefixAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 管理 API（{@code /admin/} 下的路径）的认证过滤器：每个请求都要带控制台用户令牌，认证后把 {@link ConsoleUser} 写入请求属性
 * （ADR-0042）。API Key 不能调用管理 API。授权由 {@code rbac} 包的拦截器在路由之后完成。
 */
public final class ConsoleUserFilter extends PrefixAuthenticationFilter<ConsoleUser> {

    /** 请求属性的键：认证后的 {@link ConsoleUser}。 */
    public static final String PRINCIPAL_ATTRIBUTE = ConsoleUserFilter.class.getName() + ".principal";

    /** 需要控制台身份的路径的第一段：管理 API 的前缀 {@code /admin/v1/}。 */
    public static final String ADMIN_SEGMENT = "admin";

    /** 令牌的长度上限：OIDC access token 通常在 2 KB 以内，留足余量。 */
    static final int MAX_TOKEN_LENGTH = 8_192;

    private final ConsoleUserResolver resolver;

    /**
     * 创建过滤器。
     *
     * @param resolver 控制台用户令牌的来源
     * @param errors Spring MVC 的异常解析器，输出认证失败的 problem+json
     */
    public ConsoleUserFilter(ConsoleUserResolver resolver, HandlerExceptionResolver errors) {
        super(ADMIN_SEGMENT, PRINCIPAL_ATTRIBUTE, errors);
        this.resolver = resolver;
    }

    /**
     * 当前请求的控制台用户。
     *
     * @param request 当前请求
     * @return 用户
     * @throws MeridianException 请求没有经过认证时（{@code auth.unauthenticated}）
     */
    public static ConsoleUser require(HttpServletRequest request) {
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof ConsoleUser user) {
            return user;
        }
        throw new MeridianException(ConsoleAuthErrorCode.UNAUTHENTICATED, "管理 API 需要控制台用户令牌");
    }

    @Override
    protected ConsoleUser authenticate(String authorization) {
        String token = BearerTokens.parse(authorization, MAX_TOKEN_LENGTH)
                .orElseThrow(() -> new MeridianException(
                        ConsoleAuthErrorCode.UNAUTHENTICATED, "管理 API 需要 Authorization: Bearer <控制台用户令牌>"));
        return resolver.resolve(token)
                .orElseThrow(() -> new MeridianException(ConsoleAuthErrorCode.INVALID_CONSOLE_TOKEN, "控制台令牌无效或已过期"));
    }
}
