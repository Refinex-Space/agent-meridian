package cn.refinex.meridian.runtime.webhook.app;

import cn.refinex.meridian.common.net.EndpointPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Webhook 投递的配置（{@code meridian.runtime.webhook}，ADR-0049）。
 *
 * @param enabled 是否在本节点运行投递器（测试关掉后手动驱动 {@link WebhookDispatcher#runOnce()}）
 * @param interval 投递器的扫描间隔，默认 2 秒
 * @param batchSize 每次扫描至多扇出的事件数与认领的投递记录数
 * @param parallelism 一次扫描中并发发出的请求数上限
 * @param requestTimeout 单次投递的超时（连接加等待响应），默认 10 秒
 * @param claimDuration 认领的有效期：超过它还没记录结果的投递由任一节点重新投递（至少一次），大于 {@code requestTimeout}
 * @param subscriptionCacheTtl 项目订阅的缓存时间，默认 60 秒（另由 {@code config.invalidate} 的 {@code webhook} 淘汰）
 * @param deliveryRetention 投递记录的保留期，默认 30 天
 * @param allowLoopback 是否放行回环地址的接收方，只在开发与测试为真
 * @param privateNetworks 放行的内网与保留网段（CIDR）；默认不放行，与 control-plane 的 {@code webhooks.private-networks} 一致
 */
@ConfigurationProperties("meridian.runtime.webhook")
public record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("16") int parallelism,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("60s") Duration claimDuration,
        @DefaultValue("60s") Duration subscriptionCacheTtl,
        @DefaultValue("30d") Duration deliveryRetention,
        @DefaultValue("false") boolean allowLoopback,
        List<String> privateNetworks) {

    /**
     * 规整并校验。
     *
     * @throws IllegalArgumentException 取值不合法时
     */
    public WebhookProperties {
        privateNetworks = privateNetworks == null ? List.of() : List.copyOf(privateNetworks);
        if (batchSize < 1 || parallelism < 1) {
            throw new IllegalArgumentException("meridian.runtime.webhook 的 batch-size、parallelism 至少为 1");
        }
        if (claimDuration.compareTo(requestTimeout) <= 0) {
            throw new IllegalArgumentException("meridian.runtime.webhook.claim-duration 必须大于 request-timeout");
        }
    }

    /**
     * 接收方地址的策略。
     *
     * @return 策略
     */
    public EndpointPolicy policy() {
        return new EndpointPolicy(allowLoopback, privateNetworks);
    }
}
