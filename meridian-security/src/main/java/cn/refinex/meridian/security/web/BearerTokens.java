package cn.refinex.meridian.security.web;

import java.util.Locale;
import java.util.Optional;

/**
 * 从 {@code Authorization} 请求头取出 {@code Bearer} 令牌：API Key、服务凭据与控制台用户令牌共用的解析规则。
 */
public final class BearerTokens {

    private static final String BEARER = "bearer ";

    private BearerTokens() {}

    /**
     * 解析 {@code Authorization: Bearer <令牌>}。方案名不区分大小写，令牌去掉首尾空白。
     *
     * @param authorization 请求头的值，可为空
     * @param maxLength 令牌的长度上限，超长视为格式不对
     * @return 令牌；缺少请求头、方案不是 Bearer、令牌为空或超长时为空
     */
    public static Optional<String> parse(String authorization, int maxLength) {
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
            return Optional.empty();
        }
        String token = authorization.substring(BEARER.length()).strip();
        if (token.isEmpty() || token.length() > maxLength) {
            return Optional.empty();
        }
        return Optional.of(token);
    }
}
