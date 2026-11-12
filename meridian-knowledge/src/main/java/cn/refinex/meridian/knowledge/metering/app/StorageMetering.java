package cn.refinex.meridian.knowledge.metering.app;

import cn.refinex.meridian.knowledge.metering.infra.system.StorageUsageRepository;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每日上报知识库存储量（架构设计「跨服务数据流」：knowledge 经 outbox 的 {@code usage} 主题上报存储 GB 天，ADR-0051）。
 *
 * <p>每次执行上报前一天（UTC）还没有上报、至少有一份文档的知识库：大小取执行时的原文件与切片字节数（不是当天的平均值）。
 * {@code kb.storage_reports} 与 outbox 在同一事务写入，多个实例或重复执行不会重复上报。
 */
@Service
public class StorageMetering {

    private static final Logger log = LoggerFactory.getLogger(StorageMetering.class);

    /** outbox 主题。 */
    public static final String TOPIC = "usage";

    /** 一批处理的知识库数。 */
    static final int BATCH = 200;

    private final StorageUsageRepository usage;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param usage 存储量统计
     * @param outbox outbox 写入器
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public StorageMetering(
            StorageUsageRepository usage, OutboxWriter outbox, TransactionTemplate transactions, Clock clock) {
        this.usage = usage;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 上报前一天的存储量。
     *
     * @return 本次上报的知识库数
     */
    public int reportYesterday() {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
        int reported = 0;
        while (true) {
            List<StorageUsageRepository.Usage> batch = usage.unreported(day, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (StorageUsageRepository.Usage item : batch) {
                if (report(item, day, now)) {
                    reported++;
                }
            }
        }
        if (reported > 0) {
            log.info("知识库存储量已上报 day={} knowledgeBases={}", day, reported);
        }
        return reported;
    }

    private boolean report(StorageUsageRepository.Usage item, LocalDate day, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!usage.markReported(item, day, now)) {
                return false;
            }
            outbox.append(
                    TOPIC,
                    StorageUsageMessage.of(
                            item.tenantId(),
                            item.projectId(),
                            item.knowledgeBaseId(),
                            day,
                            item.fileBytes(),
                            item.indexBytes()));
            return true;
        }));
    }
}
