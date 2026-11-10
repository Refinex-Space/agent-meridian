package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.TenantQuotaListener;
import cn.refinex.meridian.runtime.dispatch.app.TenantQuotaPort;
import cn.refinex.meridian.runtime.dispatch.infra.ControlPlaneTenantQuotas;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import cn.refinex.meridian.runtime.web.ApiKeyRevocationListener;
import cn.refinex.meridian.security.apikey.ApiKeyResolver;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 与 control-plane 的对接（T14a，ADR-0042）：配置了 {@code meridian.security.service-client.control-plane-url} 时，API Key 经
 * control-plane 校验（{@code meridian-security} 的自动配置），这里再装配吊销与配置变更的订阅、租户并发配额的来源；没有配置时（测试）
 * 配额取 {@code default-tenant-max-running}，不订阅。
 */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfiguration {

    /** control-plane 地址的配置键（{@link ServiceClientProperties#controlPlaneUrl()}）。 */
    static final String CONTROL_PLANE_URL = "meridian.security.service-client.control-plane-url";

    /**
     * 租户并发配额的来源。
     *
     * @param serviceClient 服务间调用配置
     * @param clients 服务间调用客户端的构造入口
     * @param dispatch 调度配置，没有 control-plane 时取默认值
     * @return 配额来源
     */
    @Bean
    public TenantQuotaPort tenantQuotaPort(
            ServiceClientProperties serviceClient, ServiceRestClients clients, DispatchProperties dispatch) {
        if (!serviceClient.hasControlPlane()) {
            return tenantId -> dispatch.defaultTenantMaxRunning();
        }
        return new ControlPlaneTenantQuotas(clients.create(serviceClient.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE));
    }

    /**
     * 订阅 {@code config.invalidate}，更新租户并发上限。
     *
     * @param redis Redis 消息封装
     * @param quotas 配额来源
     * @param queue 轮次队列仓储
     * @param clock 时钟
     * @return 订阅组件
     */
    @Bean
    @ConditionalOnProperty(CONTROL_PLANE_URL)
    public TenantQuotaListener tenantQuotaListener(
            ReactiveRedisMessaging redis, TenantQuotaPort quotas, TurnQueueRepository queue, Clock clock) {
        return new TenantQuotaListener(redis, quotas, queue, clock);
    }

    /**
     * 订阅 {@code key.revoked}，淘汰 API Key 的校验缓存。配置了 control-plane 时，{@code meridian-security} 装配的解析器就是
     * {@link ControlPlaneApiKeyResolver}。
     *
     * @param resolver API Key 解析器
     * @param redis Redis 消息封装
     * @return 订阅组件
     */
    @Bean
    @ConditionalOnProperty(CONTROL_PLANE_URL)
    public ApiKeyRevocationListener apiKeyRevocationListener(ApiKeyResolver resolver, ReactiveRedisMessaging redis) {
        return new ApiKeyRevocationListener(redis, (ControlPlaneApiKeyResolver) resolver);
    }
}
