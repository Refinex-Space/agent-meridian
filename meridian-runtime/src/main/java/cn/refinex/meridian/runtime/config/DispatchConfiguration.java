package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.LeaseReaper;
import cn.refinex.meridian.runtime.dispatch.app.LeaseRenewer;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度的装配：节点身份，以及随容器启停的续租任务与回收器（架构设计「租约」「租约回收」）。
 *
 * <p>两个后台任务各在一个虚拟线程上按固定延迟执行（{@link PeriodicJob}），不占用 Reactor 线程；多节点同时运行回收器是安全的，
 * 它用 {@code SKIP LOCKED} 逐个处理过期租约。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DispatchConfiguration.class);

    /**
     * 本节点 ID：配置了 {@code meridian.runtime.dispatch.node-id} 时用它，否则按环境变量 {@code HOSTNAME}（Kubernetes 中为 Pod 名）
     * 加随机后缀生成。
     *
     * @param properties 调度配置
     * @return 节点 ID
     */
    @Bean
    public NodeId nodeId(DispatchProperties properties) {
        NodeId nodeId = properties.nodeId() == null || properties.nodeId().isBlank()
                ? NodeId.generate(System.getenv("HOSTNAME"))
                : new NodeId(properties.nodeId());
        log.info("runtime 节点 ID nodeId={}", nodeId);
        return nodeId;
    }

    /**
     * 续租任务，每 {@code renew-interval}（默认 10 秒）运行一次。
     *
     * @param renewer 续租用例
     * @param properties 调度配置
     * @return 后台任务
     */
    @Bean
    public PeriodicJob leaseRenewerJob(LeaseRenewer renewer, DispatchProperties properties) {
        return new PeriodicJob("lease-renewer", properties.renewInterval(), renewer::renewAll);
    }

    /**
     * 租约回收器，每 {@code reap-interval}（默认 5 秒）运行一次。
     *
     * @param reaper 回收器
     * @param properties 调度配置
     * @return 后台任务
     */
    @Bean
    public PeriodicJob leaseReaperJob(LeaseReaper reaper, DispatchProperties properties) {
        return new PeriodicJob("lease-reaper", properties.reapInterval(), reaper::reapExpired);
    }
}
