package cn.refinex.meridian.controlplane.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * control-plane 的配置（{@code meridian.control-plane}，ADR-0042）。
 *
 * @param tenants 新租户的默认配额
 * @param apiKeys API Key 的默认值
 * @param platformAdmins 平台管理员的主体标识；可为空列表，表示没有人能创建租户
 */
@ConfigurationProperties("meridian.control-plane")
public record ControlPlaneProperties(
        @DefaultValue Tenants tenants, @DefaultValue ApiKeys apiKeys, List<String> platformAdmins) {

    /** 规整空列表。 */
    public ControlPlaneProperties {
        platformAdmins = platformAdmins == null ? List.of() : List.copyOf(platformAdmins);
    }

    /**
     * 新租户的默认配额。
     *
     * @param defaultMaxConcurrentTurns 并发配额，默认 20（架构设计「认领」中的租户并发计数行，ADR-0027）
     * @param defaultEventRetentionDays 事件保留天数，默认 365（架构设计「数据保留」）
     */
    public record Tenants(
            @DefaultValue("20") int defaultMaxConcurrentTurns,
            @DefaultValue("365") int defaultEventRetentionDays) {}

    /**
     * API Key 的默认值。
     *
     * @param defaultRateLimitPerMinute 创建时不给出上限的默认值，默认 600（ADR-0042）
     */
    public record ApiKeys(@DefaultValue("600") int defaultRateLimitPerMinute) {}
}
