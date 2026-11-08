package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriver;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxDriverSettings;
import cn.refinex.meridian.engine.agentscope.sandbox.TurnSandboxes;
import cn.refinex.meridian.engine.spi.port.SandboxStatePort;
import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.runtime.sandbox.app.IdleSandboxReaper;
import cn.refinex.meridian.runtime.sandbox.app.SandboxProperties;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话沙箱的装配（T12a，架构设计「会话沙箱」，ADR-0036、ADR-0037）。{@code meridian.runtime.sandbox.enabled=true} 时连接沙箱集群：
 * Kubernetes 客户端、{@link KubernetesSandboxDriver}、每轮注入外部沙箱的 {@link TurnSandboxes}、空闲回收器。关闭时没有驱动，
 * {@code sandbox} 档的轮次以错误结束，{@code none} 档不受影响。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {

    /**
     * 由配置得到驱动配置。
     *
     * @param properties 沙箱配置
     * @return 驱动配置
     */
    static SandboxDriverSettings driverSettings(SandboxProperties properties) {
        SandboxDriverSettings.Connection connection = switch (properties.connection()) {
            case SandboxProperties.DIRECT -> new SandboxDriverSettings.Direct(properties.routerUrl());
            case SandboxProperties.GATEWAY ->
                new SandboxDriverSettings.Gateway(
                        properties.gatewayName(), properties.gatewayNamespace(), properties.gatewayScheme());
            default -> new SandboxDriverSettings.PortForward();
        };
        return new SandboxDriverSettings(
                properties.namespace(),
                properties.warmPoolPrefix(),
                SandboxDriverSettings.defaults(connection).workspaceRoot(),
                connection,
                properties.readyTimeout(),
                properties.execTimeout());
    }

    /** 沙箱集群已配置时的组件。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "meridian.runtime.sandbox", name = "enabled", havingValue = "true")
    static class Enabled {

        /**
         * 访问沙箱命名空间的 Kubernetes 客户端：需要 chart 中 runtime 的 Role（认领与删除 {@code SandboxClaim}、读 {@code Sandbox}、
         * 端口转发）。
         *
         * @param properties 沙箱配置，{@code kube-context} 为空时按 fabric8 的默认规则
         * @return 客户端
         */
        @Bean(destroyMethod = "close")
        KubernetesClient sandboxKubernetesClient(SandboxProperties properties) {
            Config config =
                    properties.kubeContext() == null || properties.kubeContext().isBlank()
                            ? Config.autoConfigure(null)
                            : Config.autoConfigure(properties.kubeContext());
            return new KubernetesClientBuilder().withConfig(config).build();
        }

        /**
         * 沙箱驱动。
         *
         * @param properties 沙箱配置
         * @param kube Kubernetes 客户端
         * @param snapshots 快照存储
         * @return 驱动
         */
        @Bean(destroyMethod = "close")
        KubernetesSandboxDriver sandboxDriver(
                SandboxProperties properties, KubernetesClient kube, SnapshotStorePort snapshots) {
            return new KubernetesSandboxDriver(driverSettings(properties), kube, snapshots);
        }

        /**
         * 每轮的外部沙箱：内核经它读出 runtime 准备好的会话沙箱。
         *
         * @param states 会话沙箱状态端口
         * @param driver 沙箱驱动
         * @return 提供者
         */
        @Bean
        TurnSandboxes turnSandboxes(SandboxStatePort states, KubernetesSandboxDriver driver) {
            return new TurnSandboxes(states, driver);
        }

        /**
         * 空闲回收器。
         *
         * @param idle 空闲扫描
         * @param sandboxes 沙箱服务
         * @param properties 沙箱配置
         * @param clock 时钟
         * @return 回收器
         */
        @Bean
        IdleSandboxReaper idleSandboxReaper(
                IdleSandboxRepository idle, SandboxService sandboxes, SandboxProperties properties, Clock clock) {
            return new IdleSandboxReaper(idle, sandboxes, properties, clock);
        }

        /**
         * 空闲回收的后台任务，与租约任务同一启动阶段。
         *
         * @param reaper 回收器
         * @param properties 沙箱配置
         * @return 后台任务
         */
        @Bean
        PeriodicJob idleSandboxReaperJob(IdleSandboxReaper reaper, SandboxProperties properties) {
            return new PeriodicJob(
                    "idle-sandbox-reaper",
                    properties.reapInterval(),
                    reaper::reapOnce,
                    TurnWorkerLifecycle.LEASE_JOBS_PHASE);
        }
    }
}
