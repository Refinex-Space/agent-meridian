package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.api.internal.model.ResolvedSecret;
import cn.refinex.meridian.api.internal.model.SecretResolveRequest;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Mono;

/**
 * {@link SecretResolver} 的生产实现（架构设计「凭据」，ADR-0045 第 3、4 条）：以服务 JWT 调 control-plane 的
 * {@code POST /internal/v1/secrets:resolve}，按"租户、项目、凭据"缓存明文，写入后 {@code secret-cache-ttl}（默认 5 分钟）过期——这就是
 * 明文在 runtime 内存中的上限；凭据轮换或删除时 {@code config.invalidate} 立即淘汰（{@link #evict}）。
 *
 * <p>租户与项目取自 Reactor Context 中的 {@code PlatformContext}，缓存键含二者，命中不会跨租户或跨项目；control-plane 校验凭据属于它们。
 * 凭据不存在或不属于调用方以 {@code NotFoundException} 失败（不缓存），control-plane 不可用以可重试的
 * {@link ProviderUnavailableException} 失败。明文只放进 {@link SecretValue}，不记日志、不进异常消息。
 *
 * <p>线程安全：可被并发调用。
 */
public final class ControlPlaneSecretResolver implements SecretResolver {

    /** 缓存容量。 */
    static final int MAX_ENTRIES = 10_000;

    private final RestClient client;
    private final Cache<Key, SecretValue> cache;

    /**
     * 创建解析器。
     *
     * @param client 指向 control-plane 的服务间调用客户端（{@code ServiceRestClients.create}）
     * @param ttl 明文的缓存时间
     */
    public ControlPlaneSecretResolver(RestClient client, Duration ttl) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ttl)
                .build();
    }

    @Override
    public Mono<SecretValue> resolve(String secretId) {
        return PlatformContexts.currentReactive()
                .flatMap(context -> Blocking.call(() -> cache.get(key(context, secretId), this::fetch)));
    }

    /**
     * 淘汰一个凭据在全部租户、项目下的缓存（{@code config.invalidate} 的 {@code secret}）。
     *
     * @param secretId 凭据 ID
     */
    public void evict(String secretId) {
        cache.asMap().keySet().removeIf(key -> key.secretId().equals(secretId));
    }

    private SecretValue fetch(Key key) {
        ResolvedSecret resolved;
        try {
            resolved = client.post()
                    .uri("/internal/v1/secrets:resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SecretResolveRequest(key.tenantId(), key.secretId()).projectId(key.projectId()))
                    .retrieve()
                    .body(ResolvedSecret.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new NotFoundException(
                        ModelRoutingErrorCode.CREDENTIAL_UNAVAILABLE, "凭据不存在或不属于当前项目：" + key.secretId());
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (resolved == null) {
            throw unavailable(null);
        }
        return new SecretValue(resolved.getValue());
    }

    private static ProviderUnavailableException unavailable(Throwable cause) {
        return new ProviderUnavailableException("凭据暂时无法解析，请稍后重试", cause);
    }

    private static Key key(PlatformContext context, String secretId) {
        return new Key(context.tenantId(), context.projectId(), secretId);
    }

    /**
     * 缓存键。
     *
     * @param tenantId 调用方租户
     * @param projectId 调用方项目；可为空
     * @param secretId 凭据 ID
     */
    record Key(String tenantId, String projectId, String secretId) {}
}
