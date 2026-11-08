package cn.refinex.meridian.runtime.modelrouting.app;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型路由的配置，前缀 {@code meridian.runtime.model-routing}。
 *
 * @param rateLimitMaxWait 限流等待的上限，默认 30 秒（架构设计「模型调用」第 2 步），超过抛 {@code RateLimitTimeoutException}
 * @param reservationTtl 预扣记录在 Redis 中的保留时间，默认 1 小时；远大于一次模型调用的时长，过期未结算的预扣由对账修正
 * @param providersDirectory 开发期供应商与模型目录（T15a 之前），默认相对于工作目录的 {@code dev-config/providers}
 * @param budgetsDirectory 开发期预算限额（T16 之前），默认 {@code dev-config/budgets}；不存在时不限
 */
@ConfigurationProperties("meridian.runtime.model-routing")
public record ModelRoutingProperties(
        @DefaultValue("30s") Duration rateLimitMaxWait,
        @DefaultValue("1h") Duration reservationTtl,
        @DefaultValue("dev-config/providers") Path providersDirectory,
        @DefaultValue("dev-config/budgets") Path budgetsDirectory) {

    /**
     * 校验取值。
     *
     * @throws IllegalArgumentException 时长不为正时
     */
    public ModelRoutingProperties {
        if (rateLimitMaxWait == null || rateLimitMaxWait.isNegative() || rateLimitMaxWait.isZero()) {
            throw new IllegalArgumentException("rate-limit-max-wait 必须为正：" + rateLimitMaxWait);
        }
        if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservation-ttl 必须为正：" + reservationTtl);
        }
    }

    /**
     * 全部取默认值的配置，供测试与手工装配使用。
     *
     * @return 配置
     */
    public static ModelRoutingProperties defaults() {
        return new ModelRoutingProperties(
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                Path.of("dev-config/providers"),
                Path.of("dev-config/budgets"));
    }
}
