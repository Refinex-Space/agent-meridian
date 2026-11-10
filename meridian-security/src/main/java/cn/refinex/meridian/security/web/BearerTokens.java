package cn.refinex.meridian.security.web;

import java.util.Locale;
import java.util.Optional;

/**
 * 从 {@code Authorization} 请求头取出 {@code Bearer} 令牌：API Key、服务 JWT 与控制台用户令牌共用的解析规则。
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

    /**
     * 令牌是否是 JWS 紧凑序列化的形状（三段，以点分隔，各段非空）。公开 API 据此区分 OIDC 令牌与 API Key（ADR-0044），服务 JWT
     * 的校验据此跳过明显不是 JWT 的值；只看形状，不代表签名有效。
     *
     * @param token 令牌
     * @return 是 JWT 的形状时为 true
     */
    public static boolean looksLikeJwt(String token) {
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        return first > 0 && second > first + 1 && second < token.length() - 1 && token.indexOf('.', second + 1) < 0;
    }
}
