package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.common.net.EndpointPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型路由的配置，前缀 {@code meridian.runtime.model-routing}。
 *
 * @param rateLimitMaxWait 限流等待的上限，默认 30 秒（架构设计「模型调用」第 2 步），超过抛 {@code RateLimitTimeoutException}
 * @param reservationTtl 预扣记录在 Redis 中的保留时间，默认 1 小时；远大于一次模型调用的时长，过期未结算的预扣由对账修正
 * @param providersDirectory 开发与测试的供应商与模型目录，{@code catalog-source=file} 时使用，默认相对于工作目录的
 *     {@code dev-config/providers}
 * @param budgetsDirectory 开发期预算限额（T16 之前），默认 {@code dev-config/budgets}；不存在时不限
 * @param catalogSource 目录与凭据的来源：{@code control-plane}（默认，经内部接口解析，ADR-0045）或 {@code file}（只在 {@code dev}、
 *     {@code test} profile：{@code providersDirectory} 加环境变量中的 {@code *_API_KEY}）
 * @param catalogCacheTtl 模型目录的缓存时间，默认 60 秒：{@code config.invalidate} 丢失时的兜底
 * @param secretCacheTtl 凭据明文在内存中的缓存时间，默认 5 分钟（架构设计「凭据」）
 * @param allowLoopbackEndpoints 供应商端点是否放行回环地址：只在开发与测试为真（ADR-0045 第 5 条）
 * @param privateNetworks 供应商端点放行的私有网段；不配置时为全部私有网段
 */
@ConfigurationProperties("meridian.runtime.model-routing")
public record ModelRoutingProperties(
        @DefaultValue("30s") Duration rateLimitMaxWait,
        @DefaultValue("1h") Duration reservationTtl,
        @DefaultValue("dev-config/providers") Path providersDirectory,
        @DefaultValue("dev-config/budgets") Path budgetsDirectory,
        @DefaultValue(CONTROL_PLANE) String catalogSource,
        @DefaultValue("60s") Duration catalogCacheTtl,
        @DefaultValue("5m") Duration secretCacheTtl,
        boolean allowLoopbackEndpoints,
        List<String> privateNetworks) {

    /** 目录与凭据来自 control-plane。 */
    public static final String CONTROL_PLANE = "control-plane";

    /** 目录与凭据来自开发配置文件与环境变量（只在开发与测试）。 */
    public static final String FILE = "file";

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
        if (!CONTROL_PLANE.equals(catalogSource) && !FILE.equals(catalogSource)) {
            throw new IllegalArgumentException("catalog-source 必须是 control-plane 或 file：" + catalogSource);
        }
        privateNetworks =
                privateNetworks == null ? EndpointPolicy.DEFAULT_PRIVATE_NETWORKS : List.copyOf(privateNetworks);
    }

    /**
     * 供应商端点的地址策略。
     *
     * @return 策略
     */
    public EndpointPolicy endpointPolicy() {
        return new EndpointPolicy(allowLoopbackEndpoints, privateNetworks);
    }

    /**
     * 测试与手工装配用的配置：文件目录，放行回环端点。
     *
     * @return 配置
     */
    public static ModelRoutingProperties defaults() {
        return new ModelRoutingProperties(
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                Path.of("dev-config/providers"),
                Path.of("dev-config/budgets"),
                FILE,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                true,
                null);
    }
}
