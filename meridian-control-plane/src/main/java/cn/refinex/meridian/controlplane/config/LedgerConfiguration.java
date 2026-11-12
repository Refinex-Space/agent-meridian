package cn.refinex.meridian.controlplane.config;

import cn.refinex.meridian.controlplane.audit.app.AuditIngestion;
import cn.refinex.meridian.controlplane.budget.app.BudgetLimitSync;
import cn.refinex.meridian.controlplane.budget.app.BudgetReconciliation;
import cn.refinex.meridian.controlplane.metering.app.LedgerPartitions;
import cn.refinex.meridian.controlplane.metering.app.UsageIngestion;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.persistence.redis.StreamConsumer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用量账本、审计与预算的后台作业（ADR-0048）：Stream {@code usage} 与 {@code audit} 的消费者（消费组 {@value #GROUP}），账本分区维护、
 * 限额全量同步、预算对账。启动时先执行一次分区维护与限额同步。
 */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

    /** 用量与审计的消费组：全部 control-plane 实例同组，每条消息只投递给一个实例。 */
    public static final String GROUP = "control-plane";

    private static final Logger log = LoggerFactory.getLogger(LedgerConfiguration.class);

    /** 本实例在消费组中的名字：主机名加随机后缀，同一主机上的多个实例也不相撞。 */
    private final String consumerName = consumerName();

    /**
     * 用量消费者。
     *
     * @param messaging Redis 客户端
     * @param ingestion 入账逻辑
     * @param clock 时钟
     * @return 消费者（随容器启停）
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "meridian.control-plane.metering.consumers-enabled", matchIfMissing = true)
    public StreamConsumer usageConsumer(RedisMessaging messaging, UsageIngestion ingestion, Clock clock) {
        return new StreamConsumer(
                messaging,
                UsageIngestion.STREAM,
                GROUP,
                consumerName,
                ingestion,
                StreamConsumer.Settings.DEFAULT,
                clock);
    }

    /**
     * 审计消费者：{@code cp.audit_logs} 唯一的写入入口。
     *
     * @param messaging Redis 客户端
     * @param ingestion 入库逻辑
     * @param clock 时钟
     * @return 消费者（随容器启停）
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "meridian.control-plane.metering.consumers-enabled", matchIfMissing = true)
    public StreamConsumer auditConsumer(RedisMessaging messaging, AuditIngestion ingestion, Clock clock) {
        return new StreamConsumer(
                messaging,
                AuditIngestion.STREAM,
                GROUP,
                consumerName,
                ingestion,
                StreamConsumer.Settings.DEFAULT,
                clock);
    }

    /**
     * 定期维护账本分区：提前创建下个月的，删除超过 3 年的。
     *
     * @param partitions 分区维护
     * @param properties 配置
     * @return 后台任务
     */
    @Bean
    public PeriodicJob ledgerPartitionJob(LedgerPartitions partitions, ControlPlaneProperties properties) {
        return new PeriodicJob("ledger-partitions", properties.metering().partitionInterval(), partitions::maintain);
    }

    /**
     * 定期全量重写 Redis 中的预算限额。
     *
     * @param sync 限额同步
     * @param properties 配置
     * @return 后台任务
     */
    @Bean
    public PeriodicJob budgetLimitSyncJob(BudgetLimitSync sync, ControlPlaneProperties properties) {
        return new PeriodicJob("budget-limit-sync", properties.metering().limitSyncInterval(), sync::syncAll);
    }

    /**
     * 定期对账预算计数。
     *
     * @param reconciliation 对账
     * @param properties 配置
     * @return 后台任务
     */
    @Bean
    public PeriodicJob budgetReconciliationJob(BudgetReconciliation reconciliation, ControlPlaneProperties properties) {
        return new PeriodicJob(
                "budget-reconciliation", properties.metering().reconciliationInterval(), reconciliation::reconcile);
    }

    /**
     * 启动时先维护一次分区、重写一次限额：定期任务要等一个间隔才第一次执行。失败只记日志，定期任务稍后重试。
     *
     * @param partitions 分区维护
     * @param sync 限额同步
     * @return 启动任务
     */
    @Bean
    public ApplicationRunner ledgerStartup(LedgerPartitions partitions, BudgetLimitSync sync) {
        return args -> {
            try {
                partitions.maintain();
                sync.syncAll();
            } catch (RuntimeException e) {
                log.warn("启动时的账本分区维护或预算限额同步失败，等待定期任务重试", e);
            }
        };
    }

    private static String consumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "control-plane";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
