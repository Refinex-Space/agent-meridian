package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.BearerTokens;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * 公开 API 的准入：认证加限流，WebFlux（{@link ApiKeyWebFilter}）与 MVC（{@link ApiKeyFilter}）两种过滤器共用（架构设计「认证」「约定」，
 * ADR-0042、ADR-0044）。
 *
 * <p>两种凭据按 {@code Bearer} 令牌的形状分流：JWT 形状的是控制台用户的 OIDC 令牌，交给 {@link ConsoleTokenAuthenticator}（必须带
 * {@code Meridian-Project}）；其他按 API Key 认证。没有控制台认证时 JWT 也按 API Key 处理（必然无效）。主体带每分钟上限且有限流器时，
 * 每个请求消耗一个令牌；限流器出错时放行并记 WARN，限流的故障不扩大为 API 不可用。
 *
 * <p>方法会阻塞（解析器可能查询 control-plane，限流器访问 Redis）。线程安全：不可变。
 */
public final class PublicApiAdmission {

    /** 响应头：上限。 */
    public static final String LIMIT_HEADER = "RateLimit-Limit";

    /** 响应头：剩余请求数。 */
    public static final String REMAINING_HEADER = "RateLimit-Remaining";

    /** 响应头：多少秒后补满。 */
    public static final String RESET_HEADER = "RateLimit-Reset";

    /** 控制台用户指定项目的请求头（架构设计「认证」中"控制台用户"一行）。 */
    public static final String PROJECT_HEADER = "Meridian-Project";

    /** 需要认证的路径的第一段：公开 API 的版本前缀（架构设计「约定」）。 */
    static final String API_SEGMENT = "v1";

    /** 控制台令牌的长度上限：OIDC access token 通常在 2 KB 以内，与管理 API 的上限相同。 */
    static final int MAX_CONSOLE_TOKEN_LENGTH = 8_192;

    private static final Logger log = LoggerFactory.getLogger(PublicApiAdmission.class);

    private final ApiKeyAuthenticator authenticator;
    private final ConsoleTokenAuthenticator consoleAuthenticator;
    private final ApiKeyRateLimiter rateLimiter;

    /**
     * 创建准入。
     *
     * @param authenticator API Key 认证器
     * @param consoleAuthenticator 控制台用户令牌的认证；可为空，表示只接受 API Key
     * @param rateLimiter 限流器；可为空，表示不限流
     */
    public PublicApiAdmission(
            ApiKeyAuthenticator authenticator,
            ConsoleTokenAuthenticator consoleAuthenticator,
            ApiKeyRateLimiter rateLimiter) {
        this.authenticator = authenticator;
        this.consoleAuthenticator = consoleAuthenticator;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 认证并限流。
     *
     * @param authorization {@code Authorization} 请求头；可为空
     * @param project {@code Meridian-Project} 请求头；可为空
     * @return 主体与限流判定
     * @throws MeridianException 认证失败时（{@code auth.*}）
     */
    public Admission admit(String authorization, String project) {
        PublicApiPrincipal principal = authenticate(authorization, project);
        return new Admission(principal, limit(principal));
    }

    private PublicApiPrincipal authenticate(String authorization, String project) {
        if (consoleAuthenticator != null) {
            Optional<String> token = BearerTokens.parse(authorization, MAX_CONSOLE_TOKEN_LENGTH);
            if (token.isPresent() && BearerTokens.looksLikeJwt(token.get())) {
                return consoleAuthenticator.authenticate(token.get(), project);
            }
        }
        return authenticator.authenticate(authorization);
    }

    private RateLimitDecision limit(PublicApiPrincipal principal) {
        if (rateLimiter == null || principal.rateLimitPerMinute() == null) {
            return null;
        }
        try {
            return rateLimiter.consume(principal.rateLimitBucket(), principal.rateLimitPerMinute());
        } catch (RuntimeException e) {
            log.warn("公开 API 限流失败，本次放行 {}", principal.describe(), e);
            return null;
        }
    }

    /**
     * 认证与限流的结果。
     *
     * @param principal 主体
     * @param decision 限流判定；不限流时为空
     */
    public record Admission(PublicApiPrincipal principal, RateLimitDecision decision) {

        /**
         * 写限流响应头，并在超限时给出要抛出的错误。
         *
         * @param headers 设置响应头的回调（头名，值）
         * @return 超限时的 429 错误（{@code rate_limit.exceeded}，已写 {@code Retry-After}）；没有超限时为空
         */
        public Optional<MeridianException> applyTo(BiConsumer<String, String> headers) {
            if (decision == null) {
                return Optional.empty();
            }
            headers.accept(LIMIT_HEADER, Integer.toString(decision.limit()));
            headers.accept(REMAINING_HEADER, Long.toString(decision.remaining()));
            headers.accept(RESET_HEADER, Long.toString(decision.resetSeconds()));
            if (decision.allowed()) {
                return Optional.empty();
            }
            headers.accept(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
            return Optional.of(new MeridianException(
                    ApiKeyErrorCode.RATE_LIMITED,
                    "请求速率超过每分钟 " + decision.limit() + " 次，请 " + decision.retryAfterSeconds() + " 秒后重试"));
        }
    }
}
