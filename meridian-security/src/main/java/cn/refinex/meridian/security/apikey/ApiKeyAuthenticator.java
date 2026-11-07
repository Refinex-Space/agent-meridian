package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.error.MeridianException;
import java.util.Locale;

/**
 * 从 {@code Authorization} 请求头认证 API Key：解析 {@code Bearer} 令牌，交给 {@link ApiKeyResolver} 找到主体
 * （架构设计「认证」中"业务系统"一行）。
 *
 * <p>方法可能阻塞（取决于解析器），WebFlux 中经 {@code Blocking} 调用。线程安全：无状态。
 */
public final class ApiKeyAuthenticator {

    private static final String BEARER = "bearer ";

    /** API Key 的长度上限，超长的头直接拒绝，不交给解析器。 */
    static final int MAX_KEY_LENGTH = 256;

    private final ApiKeyResolver resolver;

    /**
     * 创建认证器。
     *
     * @param resolver API Key 的来源
     */
    public ApiKeyAuthenticator(ApiKeyResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 认证一个请求。
     *
     * @param authorization {@code Authorization} 请求头的值，可为空
     * @return 主体
     * @throws MeridianException 缺少或格式不对时为 {@code auth.unauthenticated}；API Key 无效时为 {@code auth.invalid_api_key}
     */
    public ApiKeyPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求需要 Authorization: Bearer <API Key>");
        }
        String apiKey = authorization.substring(BEARER.length()).strip();
        if (apiKey.isEmpty() || apiKey.length() > MAX_KEY_LENGTH) {
            throw new MeridianException(ApiKeyErrorCode.UNAUTHENTICATED, "请求需要 Authorization: Bearer <API Key>");
        }
        return resolver.resolve(apiKey)
                .orElseThrow(() -> new MeridianException(ApiKeyErrorCode.INVALID_API_KEY, "API Key 无效、已吊销或已过期"));
    }
}
