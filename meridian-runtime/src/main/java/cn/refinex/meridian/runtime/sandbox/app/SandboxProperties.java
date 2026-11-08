package cn.refinex.meridian.runtime.sandbox.app;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 会话沙箱的配置，前缀 {@code meridian.runtime.sandbox}（架构设计「会话沙箱」，ADR-0036、ADR-0037）。
 *
 * @param enabled 是否连接沙箱集群；关闭时 {@code sandbox} 档的轮次以错误结束，默认关闭
 * @param kubeContext kubeconfig 中的上下文名；为空时按 fabric8 的默认规则（集群内用 ServiceAccount，集群外用当前上下文）
 * @param namespace 沙箱命名空间（Helm chart {@code meridian-sandbox} 的安装位置）
 * @param warmPoolPrefix 预热池名前缀，预热池名为前缀加规格
 * @param connection 连接方式：{@code port-forward}（本机开发）、{@code direct}（集群内直连 router，生产推荐）、{@code gateway}
 * @param routerUrl {@code direct} 方式的 sandbox-router 地址
 * @param gatewayName {@code gateway} 方式的 Gateway 名
 * @param gatewayNamespace {@code gateway} 方式的 Gateway 命名空间
 * @param gatewayScheme {@code gateway} 方式的协议
 * @param readyTimeout 认领沙箱（含预热池耗尽后冷启动）的最长等待
 * @param execTimeout 执行服务的命令超时上限，与 chart 的 {@code exec.timeoutSeconds} 一致
 * @param idleTimeout 空闲回收的阈值，默认 10 分钟（架构设计「会话沙箱」"空闲回收"）
 * @param reapInterval 回收器的扫描间隔
 * @param reapBatchSize 回收器每次扫描处理的会话上限
 * @param operationTimeout 获取、快照、删除等单个沙箱操作的等待上限；回收时它决定会话行锁最多持有多久
 * @param bashDefaultTimeout {@code bash} 不指定超时时的超时
 * @param bashMaxTimeout {@code bash} 允许指定的最大超时，不超过 {@code execTimeout}
 * @param outputMaxFileSize 产物同步的单文件上限，超过的文件不同步（驱动把文件整个读进内存）
 */
@ConfigurationProperties("meridian.runtime.sandbox")
public record SandboxProperties(
        @DefaultValue("false") boolean enabled,
        String kubeContext,
        @DefaultValue("meridian-sandbox") String namespace,
        @DefaultValue("sandbox-") String warmPoolPrefix,
        @DefaultValue("port-forward") String connection,
        String routerUrl,
        String gatewayName,
        String gatewayNamespace,
        @DefaultValue("http") String gatewayScheme,
        @DefaultValue("180s") Duration readyTimeout,
        @DefaultValue("600s") Duration execTimeout,
        @DefaultValue("10m") Duration idleTimeout,
        @DefaultValue("60s") Duration reapInterval,
        @DefaultValue("20") int reapBatchSize,
        @DefaultValue("10m") Duration operationTimeout,
        @DefaultValue("120s") Duration bashDefaultTimeout,
        @DefaultValue("600s") Duration bashMaxTimeout,
        @DefaultValue("64MB") DataSize outputMaxFileSize) {

    /** 连接方式：本机端口转发。 */
    public static final String PORT_FORWARD = "port-forward";

    /** 连接方式：集群内直连 router。 */
    public static final String DIRECT = "direct";

    /** 连接方式：经 Gateway。 */
    public static final String GATEWAY = "gateway";

    /**
     * 校验取值。
     *
     * @throws IllegalArgumentException 取值不合法时
     */
    public SandboxProperties {
        connection = connection == null ? PORT_FORWARD : connection.toLowerCase(Locale.ROOT);
        switch (connection) {
            case PORT_FORWARD -> {
                // 不需要额外配置
            }
            case DIRECT -> {
                if (enabled && (routerUrl == null || routerUrl.isBlank())) {
                    throw new IllegalArgumentException("connection=direct 时必须配置 router-url");
                }
            }
            case GATEWAY -> {
                if (enabled && (gatewayName == null || gatewayNamespace == null)) {
                    throw new IllegalArgumentException("connection=gateway 时必须配置 gateway-name 与 gateway-namespace");
                }
            }
            default -> throw new IllegalArgumentException("未知的沙箱连接方式：" + connection);
        }
        requirePositive("idle-timeout", idleTimeout);
        requirePositive("reap-interval", reapInterval);
        requirePositive("operation-timeout", operationTimeout);
        requirePositive("bash-default-timeout", bashDefaultTimeout);
        if (reapBatchSize < 1) {
            throw new IllegalArgumentException("reap-batch-size 必须为正：" + reapBatchSize);
        }
        if (bashMaxTimeout.compareTo(execTimeout) > 0 || bashDefaultTimeout.compareTo(bashMaxTimeout) > 0) {
            throw new IllegalArgumentException("bash 的超时必须满足 默认 ≤ 最大 ≤ exec-timeout");
        }
    }

    /**
     * 全部取默认值、未启用的配置，供测试与手工装配使用。
     *
     * @return 配置
     */
    public static SandboxProperties defaults() {
        return new SandboxProperties(
                false,
                null,
                "meridian-sandbox",
                "sandbox-",
                PORT_FORWARD,
                null,
                null,
                null,
                "http",
                Duration.ofSeconds(180),
                Duration.ofSeconds(600),
                Duration.ofMinutes(10),
                Duration.ofSeconds(60),
                20,
                Duration.ofMinutes(10),
                Duration.ofSeconds(120),
                Duration.ofSeconds(600),
                DataSize.ofMegabytes(64));
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 必须为正：" + value);
        }
    }
}
