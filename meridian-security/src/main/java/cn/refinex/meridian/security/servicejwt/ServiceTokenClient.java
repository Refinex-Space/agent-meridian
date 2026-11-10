package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.api.internal.model.ServiceToken;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 服务 JWT 的签发客户端（ADR-0044）：用本服务的客户端密钥在 control-plane 的 {@value ServiceJwts#TOKEN_PATH} 换取令牌，按目标服务
 * 缓存，剩余有效期不足 {@link #REFRESH_BEFORE} 时重新换取。
 *
 * <p>同一目标服务同时只有一个线程在换取，其他线程等它完成后复用结果。换取失败抛 503（调用方按依赖不可用处理），不缓存失败。
 * 线程安全；不记录密钥与令牌。
 */
public final class ServiceTokenClient implements ServiceCredentialProvider {

    /** 过期前多久重新换取：令牌有效 5 分钟，留 1 分钟给时钟偏差与慢请求。 */
    static final Duration REFRESH_BEFORE = Duration.ofSeconds(60);

    private final RestClient client;
    private final String basicAuthorization;
    private final Clock clock;
    private final ConcurrentMap<String, Cached> tokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 创建客户端。
     *
     * @param client 指向 control-plane 的 HTTP 客户端（不带服务凭据）
     * @param service 本服务名（客户端 ID）
     * @param clientSecret 本服务的客户端密钥
     * @param clock 时钟，判断令牌何时刷新
     */
    public ServiceTokenClient(RestClient client, String service, String clientSecret, Clock clock) {
        this.client = client;
        // RFC 6749 2.3.1：客户端 ID 与密钥先按表单编码，再做 Basic 认证
        String pair = URLEncoder.encode(service, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        this.basicAuthorization = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    @Override
    public String credential(String audience) {
        Cached cached = tokens.get(audience);
        if (cached != null && clock.instant().isBefore(cached.refreshAt())) {
            return cached.token();
        }
        ReentrantLock lock = locks.computeIfAbsent(audience, key -> new ReentrantLock());
        lock.lock();
        try {
            cached = tokens.get(audience);
            if (cached != null && clock.instant().isBefore(cached.refreshAt())) {
                return cached.token();
            }
            Cached fresh = fetch(audience);
            tokens.put(audience, fresh);
            return fresh.token();
        } finally {
            lock.unlock();
        }
    }

    private Cached fetch(String audience) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", ServiceJwts.GRANT_TYPE);
        form.add("audience", audience);
        Instant requestedAt = clock.instant();
        ServiceToken token;
        try {
            token = client.post()
                    .uri(ServiceJwts.TOKEN_PATH)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthorization)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(ServiceToken.class);
        } catch (RestClientException e) {
            throw new MeridianException(
                    CommonErrorCode.SERVICE_UNAVAILABLE, "无法从 control-plane 取得调用 " + audience + " 的服务令牌", e);
        }
        if (token == null || token.getAccessToken() == null || token.getExpiresIn() == null) {
            throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "control-plane 的令牌响应不完整");
        }
        Duration lifetime = Duration.ofSeconds(token.getExpiresIn());
        // 有效期短于刷新提前量时（不应发生）在一半处刷新，不至于每次都换取
        Duration early =
                lifetime.compareTo(REFRESH_BEFORE.multipliedBy(2)) > 0 ? REFRESH_BEFORE : lifetime.dividedBy(2);
        return new Cached(token.getAccessToken(), requestedAt.plus(lifetime).minus(early));
    }

    /**
     * 一个缓存的令牌。
     *
     * @param token 令牌
     * @param refreshAt 从这个时刻起重新换取
     */
    private record Cached(String token, Instant refreshAt) {}
}
