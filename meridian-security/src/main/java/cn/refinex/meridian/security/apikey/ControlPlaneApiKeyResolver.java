package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.api.internal.model.ApiKeyVerification;
import cn.refinex.meridian.api.internal.model.ApiKeyVerifyRequest;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 经 control-plane 校验 API Key 的 {@link ApiKeyResolver}（架构设计「认证」，ADR-0042）：按明文的 SHA-256 调用
 * {@code POST /internal/v1/api-keys:verify}，结果（包括"无效"）按哈希缓存 60 秒；control-plane 吊销 Key 时经 Pub/Sub
 * {@value #REVOKED_CHANNEL} 广播 Key ID，应用收到后调用 {@link #evict}，各服务在 1 秒内拒绝该 Key。消息丢失时最长 60 秒后失效。
 *
 * <p>control-plane 不可用、返回非 404 的错误时抛 503，不缓存，调用方稍后重试。线程安全：缓存是 Caffeine。
 */
public final class ControlPlaneApiKeyResolver implements ApiKeyResolver {

    /** 吊销通知的 Pub/Sub 频道（架构设计「跨服务数据流」），消息内容是 API Key ID。 */
    public static final String REVOKED_CHANNEL = "key.revoked";

    /** 校验结果的缓存时长，架构设计「认证」规定 60 秒。 */
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** 缓存条目上限：每个条目只有一个主体，十万个约几十 MB，远大于一个节点上活跃的 Key 数。 */
    static final long MAX_CACHED_KEYS = 100_000;

    static final String VERIFY_PATH = "/internal/v1/api-keys:verify";

    private final RestClient client;
    private final Clock clock;
    private final Cache<String, Entry> cache;

    /**
     * 创建解析器。
     *
     * @param client 指向 control-plane 的服务间调用客户端（{@code ServiceRestClients.create}）
     * @param clock 时钟，判断 Key 是否过期
     */
    public ControlPlaneApiKeyResolver(RestClient client, Clock clock) {
        this(client, clock, Ticker.systemTicker());
    }

    /**
     * 创建解析器，指定缓存的计时器（测试用）。
     *
     * @param client 指向 control-plane 的服务间调用客户端
     * @param clock 时钟，判断 Key 是否过期
     * @param ticker 缓存过期的计时器
     */
    ControlPlaneApiKeyResolver(RestClient client, Clock clock, Ticker ticker) {
        this.client = client;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(MAX_CACHED_KEYS)
                .ticker(ticker)
                .build();
    }

    @Override
    public Optional<ApiKeyPrincipal> resolve(String apiKey) {
        Entry entry = cache.get(ApiKeyHashes.sha256(apiKey), this::verify);
        if (entry.principal().isEmpty()
                || (entry.expiresAt() != null && !clock.instant().isBefore(entry.expiresAt()))) {
            return Optional.empty();
        }
        return entry.principal();
    }

    /**
     * 淘汰某个 Key 的缓存：收到吊销通知时调用。
     *
     * @param apiKeyId API Key ID
     */
    public void evict(String apiKeyId) {
        cache.asMap()
                .values()
                .removeIf(entry -> entry.principal()
                        .map(principal -> principal.apiKeyId().equals(apiKeyId))
                        .orElse(false));
    }

    private Entry verify(String keyHash) {
        ApiKeyVerification verification;
        try {
            verification = client.post()
                    .uri(VERIFY_PATH)
                    .body(new ApiKeyVerifyRequest(keyHash))
                    .retrieve()
                    .body(ApiKeyVerification.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return new Entry(Optional.empty(), null);
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (verification == null) {
            throw unavailable(new IllegalStateException("control-plane 的校验响应为空"));
        }
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        // 不认识的作用域忽略：control-plane 先于本服务升级时，新作用域对本服务没有意义
        verification.getScopes().forEach(scope -> ApiKeyScope.fromValue(scope).ifPresent(scopes::add));
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                verification.getApiKeyId(),
                verification.getTenantId(),
                verification.getProjectId(),
                scopes,
                verification.getRateLimitPerMinute());
        Instant expiresAt = verification.getExpiresAt() == null
                ? null
                : verification.getExpiresAt().toInstant();
        return new Entry(Optional.of(principal), expiresAt);
    }

    private static MeridianException unavailable(Exception cause) {
        return new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "API Key 校验服务暂时不可用，请稍后重试", cause);
    }

    /**
     * 一条缓存：校验结果与过期时间。
     *
     * @param principal 主体；Key 无效时为空
     * @param expiresAt Key 的过期时间；可为空，表示不过期
     */
    private record Entry(Optional<ApiKeyPrincipal> principal, Instant expiresAt) {}
}
