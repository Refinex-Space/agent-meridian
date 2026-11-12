package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.runtime.retention.app.EventArchiver;
import cn.refinex.meridian.runtime.retention.app.EventRetentionPolicyPort;
import cn.refinex.meridian.runtime.retention.app.EventRetentionSweeper;
import cn.refinex.meridian.runtime.retention.app.RetentionProperties;
import cn.refinex.meridian.runtime.retention.app.SessionPurger;
import cn.refinex.meridian.runtime.retention.infra.ControlPlaneEventRetention;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据生命周期作业的装配（架构设计「数据保留」，ADR-0049）：租户保留天数的来源，以及会话清理、事件归档、保留清理三个定期任务
 * （{@code meridian.runtime.retention.enabled=false} 时不运行，测试手动驱动）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfiguration {

    /**
     * 租户的事件保留天数：配置了 control-plane 时经内部接口读取，否则取默认值。测试可以声明自己的实现。
     *
     * @param serviceClient 服务间调用配置
     * @param clients 服务间调用客户端的构造入口
     * @param properties 配置
     * @return 来源
     */
    @Bean
    @ConditionalOnMissingBean
    public EventRetentionPolicyPort eventRetentionPolicyPort(
            ServiceClientProperties serviceClient, ServiceRestClients clients, RetentionProperties properties) {
        if (!serviceClient.hasControlPlane()) {
            return tenantId -> properties.defaultEventRetentionDays();
        }
        return new ControlPlaneEventRetention(
                clients.create(serviceClient.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE));
    }

    /** 三个定期任务。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = "meridian.runtime.retention.enabled", matchIfMissing = true)
    static class Jobs {

        /**
         * 会话清理。
         *
         * @param purger 作业
         * @param properties 配置
         * @return 后台任务
         */
        @Bean
        PeriodicJob sessionPurgerJob(SessionPurger purger, RetentionProperties properties) {
            return new PeriodicJob(
                    "session-purger",
                    properties.purgeInterval(),
                    purger::purgeOnce,
                    TurnWorkerLifecycle.LEASE_JOBS_PHASE);
        }

        /**
         * 事件归档。
         *
         * @param archiver 作业
         * @param properties 配置
         * @return 后台任务
         */
        @Bean
        PeriodicJob eventArchiverJob(EventArchiver archiver, RetentionProperties properties) {
            return new PeriodicJob(
                    "event-archiver",
                    properties.archiveInterval(),
                    archiver::archiveOnce,
                    TurnWorkerLifecycle.LEASE_JOBS_PHASE);
        }

        /**
         * 事件保留清理。
         *
         * @param sweeper 作业
         * @param properties 配置
         * @return 后台任务
         */
        @Bean
        PeriodicJob eventRetentionJob(EventRetentionSweeper sweeper, RetentionProperties properties) {
            return new PeriodicJob(
                    "event-retention",
                    properties.expiryInterval(),
                    sweeper::sweepOnce,
                    TurnWorkerLifecycle.LEASE_JOBS_PHASE);
        }
    }
}
