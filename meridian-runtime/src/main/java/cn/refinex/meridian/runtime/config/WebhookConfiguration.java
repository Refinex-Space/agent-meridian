package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.webhook.app.WebhookDispatcher;
import cn.refinex.meridian.runtime.webhook.app.WebhookProperties;
import cn.refinex.meridian.runtime.webhook.app.WebhookSubscriptionPort;
import cn.refinex.meridian.runtime.webhook.infra.ControlPlaneWebhookSubscriptions;
import cn.refinex.meridian.runtime.webhook.infra.HttpWebhookSender;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 投递的装配（ADR-0049）：订阅来源（配置了 control-plane 时经内部接口读取，否则没有任何订阅）、带地址校验的 HTTP 发送、
 * {@code config.invalidate} 的订阅与投递器的定期任务（{@code meridian.runtime.webhook.enabled=false} 时不运行，测试手动驱动）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfiguration {

    /**
     * 项目的 Webhook 订阅。测试可以声明自己的实现。
     *
     * @param serviceClient 服务间调用配置
     * @param clients 服务间调用客户端的构造入口
     * @param properties Webhook 配置
     * @return 订阅来源
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookSubscriptionPort webhookSubscriptionPort(
            ServiceClientProperties serviceClient, ServiceRestClients clients, WebhookProperties properties) {
        if (!serviceClient.hasControlPlane()) {
            return (tenantId, projectId) -> List.of();
        }
        return new ControlPlaneWebhookSubscriptions(
                clients.create(serviceClient.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE),
                properties.subscriptionCacheTtl());
    }

    /**
     * 发送 Webhook 请求：地址按 {@code meridian.runtime.webhook} 的地址策略校验，连接时再校验解析结果。
     *
     * @param properties Webhook 配置
     * @return 发送器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public HttpWebhookSender webhookSender(WebhookProperties properties) {
        return new HttpWebhookSender(properties.policy(), properties.requestTimeout());
    }

    /**
     * 订阅 {@code config.invalidate} 的 {@code webhook}。
     *
     * @param redis Redis 消息封装
     * @param subscriptions 订阅来源
     * @return 订阅组件
     */
    @Bean
    public WebhookInvalidationListener webhookInvalidationListener(
            ReactiveRedisMessaging redis, WebhookSubscriptionPort subscriptions) {
        return new WebhookInvalidationListener(redis, subscriptions);
    }

    /**
     * 投递器的定期任务。
     *
     * @param dispatcher 投递器
     * @param properties Webhook 配置
     * @return 后台任务
     */
    @Bean
    @ConditionalOnProperty(value = "meridian.runtime.webhook.enabled", matchIfMissing = true)
    public PeriodicJob webhookDispatcherJob(WebhookDispatcher dispatcher, WebhookProperties properties) {
        return new PeriodicJob(
                "webhook-dispatcher", properties.interval(), dispatcher::runOnce, TurnWorkerLifecycle.LEASE_JOBS_PHASE);
    }
}
