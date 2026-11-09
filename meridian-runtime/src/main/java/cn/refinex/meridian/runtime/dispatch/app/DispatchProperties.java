package cn.refinex.meridian.runtime.dispatch.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 调度配置，前缀 {@code meridian.runtime.dispatch}。默认值取自架构设计「一轮执行的生命周期」中的「租约」「租约回收」与「认领」。
 *
 * @param nodeId 本节点 ID；可为空，表示启动时按主机名加随机后缀生成（{@code NodeId.generate}）
 * @param leaseTtl 租约时长，默认 30 秒（「租约」）
 * @param renewInterval 续租周期，默认 10 秒（「租约」），必须小于租约时长
 * @param renewFailureTimeout 距上次成功续租超过该时长仍续不上，通知执行方取消，默认 15 秒（「租约」），必须小于租约时长
 * @param reapInterval 回收器的运行周期，默认 5 秒（「租约回收」）
 * @param pollInterval Worker 没有收到唤醒通知时的轮询周期，默认 1 秒（「一轮执行的生命周期」中的唤醒）
 * @param defaultTenantMaxRunning 没有配置 control-plane 时（测试）新租户并发计数行的 {@code max_running}，默认 20；配置了 control-plane 时取租户配额（「认领」中的租户并发计数行，ADR-0027、ADR-0042）
 */
@ConfigurationProperties("meridian.runtime.dispatch")
public record DispatchProperties(
        String nodeId,
        @DefaultValue("30s") Duration leaseTtl,
        @DefaultValue("10s") Duration renewInterval,
        @DefaultValue("15s") Duration renewFailureTimeout,
        @DefaultValue("5s") Duration reapInterval,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("20") int defaultTenantMaxRunning) {

    /**
     * 校验取值。
     *
     * @throws IllegalArgumentException 时长不为正、续租周期或续租失败阈值不小于租约时长、并发上限为负时
     */
    public DispatchProperties {
        requirePositive("lease-ttl", leaseTtl);
        requirePositive("renew-interval", renewInterval);
        requirePositive("renew-failure-timeout", renewFailureTimeout);
        requirePositive("reap-interval", reapInterval);
        requirePositive("poll-interval", pollInterval);
        if (renewInterval.compareTo(leaseTtl) >= 0 || renewFailureTimeout.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException("续租周期与续租失败阈值必须小于租约时长 " + leaseTtl);
        }
        if (defaultTenantMaxRunning < 0) {
            throw new IllegalArgumentException("default-tenant-max-running 不能为负：" + defaultTenantMaxRunning);
        }
    }

    /**
     * 全部取默认值、节点 ID 由启动时生成的配置，供测试与手工装配使用。
     *
     * @return 配置
     */
    public static DispatchProperties defaults() {
        return new DispatchProperties(
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                20);
    }

    /**
     * 返回只改了新租户并发上限的副本。
     *
     * @param maxRunning 新租户的并发上限
     * @return 配置
     */
    public DispatchProperties withDefaultTenantMaxRunning(int maxRunning) {
        return new DispatchProperties(
                nodeId, leaseTtl, renewInterval, renewFailureTimeout, reapInterval, pollInterval, maxRunning);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正：" + value);
        }
    }
}
