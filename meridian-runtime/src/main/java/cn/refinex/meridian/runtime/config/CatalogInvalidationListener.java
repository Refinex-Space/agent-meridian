package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.infra.ControlPlaneModelCatalog;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * 订阅 {@code config.invalidate}，淘汰模型目录与凭据的缓存（ADR-0045 第 4 条）：{@code model} 淘汰该模型，{@code provider_account} 淘汰
 * 账号下的全部模型，{@code price_book} 淘汰引用它的模型，{@code secret} 淘汰该凭据的明文。其他类型（租户配额等）由各自的订阅者处理。
 *
 * <p>每个节点都收到并淘汰自己的缓存；Pub/Sub 不保证送达，消息丢失时由缓存的过期时间兜底（目录 60 秒、凭据 5 分钟）。
 */
public final class CatalogInvalidationListener implements SmartLifecycle {

    /** Pub/Sub 频道。 */
    public static final String CHANNEL = "config.invalidate";

    private static final Logger log = LoggerFactory.getLogger(CatalogInvalidationListener.class);

    private final ReactiveRedisMessaging redis;
    private final ModelCatalogPort catalog;
    private final SecretResolver secrets;
    private Disposable subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param catalog 模型目录：是 {@link ControlPlaneModelCatalog} 时才有缓存要淘汰
     * @param secrets 凭据解析：是 {@link ControlPlaneSecretResolver} 时才有缓存要淘汰
     */
    public CatalogInvalidationListener(ReactiveRedisMessaging redis, ModelCatalogPort catalog, SecretResolver secrets) {
        this.redis = redis;
        this.catalog = catalog;
        this.secrets = secrets;
    }

    /**
     * 处理一条消息。
     *
     * @param message 消息 JSON：{@code {"type":…,"tenant_id":…,"id":…}}
     */
    public void apply(String message) {
        JsonNode json = JsonMappers.lenient().readTree(message);
        String type = json.path("type").asString(null);
        String id = json.path("id").asString(null);
        if (type == null || id == null) {
            return;
        }
        switch (type) {
            case "model" -> {
                if (catalog instanceof ControlPlaneModelCatalog cached) {
                    cached.evictModel(id);
                }
            }
            case "provider_account" -> {
                if (catalog instanceof ControlPlaneModelCatalog cached) {
                    cached.evictAccount(id);
                }
            }
            case "price_book" -> {
                if (catalog instanceof ControlPlaneModelCatalog cached) {
                    cached.evictPriceBook(id);
                }
            }
            case "secret" -> {
                if (secrets instanceof ControlPlaneSecretResolver cached) {
                    cached.evict(id);
                }
            }
            default -> {
                return;
            }
        }
        log.debug("模型目录缓存已淘汰 type={} id={}", type, id);
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = redis.subscribe(CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .concatMap(message -> Blocking.run(() -> apply(message)).onErrorResume(error -> {
                    log.warn("处理配置变更失败 message={}", message, error);
                    return Mono.empty();
                }))
                .subscribe(ignored -> {}, error -> log.error("配置变更频道的订阅意外结束", error));
        log.info("已订阅模型目录与凭据的变更 channel={}", CHANNEL);
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return subscription != null;
    }
}
