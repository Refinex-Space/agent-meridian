package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import cn.refinex.meridian.runtime.approval.infra.ControlPlanePolicyStore;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.infra.ControlPlaneAgentConfigProvider;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * 订阅 {@code config.invalidate}，淘汰智能体配置的缓存（ADR-0047）：{@code agent} 淘汰智能体的当前版本（新会话立即用新发布的版本）与
 * 已缓存的版本，{@code environment} 淘汰环境的当前修订，{@code tenant_settings} 淘汰租户设置并淘汰该租户已装配的智能体实例（租户提示词
 * 随实例缓存，D32），{@code permission_policy} 淘汰租户或项目的策略。运行中的会话绑定了版本与修订号，不受影响。
 *
 * <p>{@code skill}、{@code mcp_server} 不需要处理：技能版本不可变，已发布的版本持有 MCP 服务的快照。模型目录与凭据由
 * {@link CatalogInvalidationListener} 处理。Pub/Sub 不保证送达，消息丢失时由缓存的过期时间兜底。
 */
public final class AgentConfigInvalidationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigInvalidationListener.class);

    private final ReactiveRedisMessaging redis;
    private final AgentConfigProvider provider;
    private final PolicyStorePort policies;
    private final ObjectProvider<AgentEngine> engine;
    private Disposable subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param provider 配置来源：是 {@link ControlPlaneAgentConfigProvider} 时才有缓存要淘汰
     * @param policies 策略来源：是 {@link ControlPlanePolicyStore} 时才有缓存要淘汰
     * @param engine 内核；Worker 关闭时不存在
     */
    public AgentConfigInvalidationListener(
            ReactiveRedisMessaging redis,
            AgentConfigProvider provider,
            PolicyStorePort policies,
            ObjectProvider<AgentEngine> engine) {
        this.redis = redis;
        this.provider = provider;
        this.policies = policies;
        this.engine = engine;
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
            case "agent" -> {
                if (provider instanceof ControlPlaneAgentConfigProvider cached) {
                    cached.evictAgent(id);
                }
            }
            case "environment" -> {
                if (provider instanceof ControlPlaneAgentConfigProvider cached) {
                    cached.evictEnvironment(id);
                }
            }
            case "tenant_settings" -> {
                if (provider instanceof ControlPlaneAgentConfigProvider cached) {
                    Set<String> versions = cached.evictTenantSettings(id);
                    AgentEngine running = engine.getIfAvailable();
                    if (running != null && !versions.isEmpty()) {
                        running.evictAgentVersions(versions);
                    }
                }
            }
            case "permission_policy" -> {
                if (policies instanceof ControlPlanePolicyStore cached) {
                    cached.evict(id);
                }
            }
            default -> {
                return;
            }
        }
        log.debug("智能体配置缓存已淘汰 type={} id={}", type, id);
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = redis.subscribe(CatalogInvalidationListener.CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .concatMap(message -> Blocking.run(() -> apply(message)).onErrorResume(error -> {
                    log.warn("处理配置变更失败 message={}", message, error);
                    return Mono.empty();
                }))
                .subscribe(ignored -> {}, error -> log.error("配置变更频道的订阅意外结束", error));
        log.info("已订阅智能体配置的变更 channel={}", CatalogInvalidationListener.CHANNEL);
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
