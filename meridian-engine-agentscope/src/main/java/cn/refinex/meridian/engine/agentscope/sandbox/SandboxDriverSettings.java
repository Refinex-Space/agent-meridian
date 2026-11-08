package cn.refinex.meridian.engine.agentscope.sandbox;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link KubernetesSandboxDriver} 的配置。
 *
 * @param namespace 沙箱所在的命名空间（Helm chart {@code meridian-sandbox} 的安装位置），sandbox-router 也在其中
 * @param warmPoolPrefix 预热池名前缀，预热池名为 {@code 前缀 + 规格}（chart 的 {@code namePrefix}）
 * @param workspaceRoot 沙箱内的工作区根目录，也是执行服务文件接口的根目录
 * @param connection runtime 连接沙箱的方式
 * @param readyTimeout 认领沙箱（含预热池耗尽后冷启动）的最长等待
 * @param execTimeout 执行服务的命令超时上限（chart 的 {@code exec.timeoutSeconds}）；驱动对沙箱的每次 HTTP 尝试的超时
 *     比它多 {@link #HTTP_TIMEOUT_MARGIN}，否则长命令会先被客户端超时打断
 */
public record SandboxDriverSettings(
        String namespace,
        String warmPoolPrefix,
        String workspaceRoot,
        Connection connection,
        Duration readyTimeout,
        Duration execTimeout) {

    /** HTTP 超时比命令超时多出的余量。 */
    public static final Duration HTTP_TIMEOUT_MARGIN = Duration.ofSeconds(60);

    /**
     * 创建配置。
     *
     * @throws NullPointerException 缺少任一字段时
     */
    public SandboxDriverSettings {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(warmPoolPrefix, "warmPoolPrefix");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(readyTimeout, "readyTimeout");
        Objects.requireNonNull(execTimeout, "execTimeout");
    }

    /**
     * 默认配置：命名空间 {@code meridian-sandbox}、预热池 {@code sandbox-<规格>}、工作区 {@code /workspace}、认领等待 180 秒、
     * 命令超时 600 秒，与 chart 的默认值一致。
     *
     * @param connection 连接方式
     * @return 配置
     */
    public static SandboxDriverSettings defaults(Connection connection) {
        return new SandboxDriverSettings(
                "meridian-sandbox",
                "sandbox-",
                "/workspace",
                connection,
                Duration.ofSeconds(180),
                Duration.ofSeconds(600));
    }

    /**
     * 返回规格对应的预热池名。
     *
     * @param size 沙箱规格
     * @return 预热池名，例如 {@code sandbox-small}
     */
    public String warmPoolName(SandboxSize size) {
        return warmPoolPrefix + size.value();
    }

    /**
     * runtime 连接沙箱执行服务的方式，对应 AgentScope 扩展的三种连接配置；都经 sandbox-router（服务 {@code sandbox-router-svc}，
     * 端口 8080）按请求头 {@code X-Sandbox-*} 转发到沙箱 Pod 的 8888 端口。
     */
    public sealed interface Connection {}

    /** 本机开发：经 Kubernetes API 对沙箱命名空间中的 sandbox-router Pod 端口转发，每个沙箱一条转发。 */
    public record PortForward() implements Connection {}

    /**
     * 直连：runtime 在集群内时直接访问 sandbox-router 服务，生产推荐方式。
     *
     * @param url router 地址，例如 {@code http://sandbox-router-svc.meridian-sandbox.svc.cluster.local:8080}
     */
    public record Direct(String url) implements Connection {

        /**
         * 创建直连配置。
         *
         * @throws NullPointerException 地址为空时
         */
        public Direct {
            Objects.requireNonNull(url, "url");
        }
    }

    /**
     * 网关：经 Gateway API 的 {@code Gateway} 资源暴露 router，runtime 在集群外时使用。
     *
     * @param name Gateway 名
     * @param gatewayNamespace Gateway 所在的命名空间
     * @param scheme {@code http} 或 {@code https}
     */
    public record Gateway(String name, String gatewayNamespace, String scheme) implements Connection {

        /**
         * 创建网关配置。
         *
         * @throws NullPointerException 缺少任一字段时
         */
        public Gateway {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(gatewayNamespace, "gatewayNamespace");
            Objects.requireNonNull(scheme, "scheme");
        }
    }
}
