package cn.refinex.meridian.controlplane.audit.app;

import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.controlplane.audit.domain.AuditMessages;
import cn.refinex.meridian.controlplane.audit.infra.system.AuditLogWriter;
import cn.refinex.meridian.persistence.redis.RedisStreamOutboxPublisher;
import cn.refinex.meridian.persistence.redis.StreamConsumer;
import cn.refinex.meridian.persistence.redis.StreamMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审计消费者的处理逻辑（ADR-0048 第 6、7 条）：归一化 Stream {@code audit} 的一批消息，在一个事务中按 {@code (id, occurred_at)} 幂等写入
 * {@code cp.audit_logs}。这是审计日志唯一的写入路径；消息所在月份的分区不存在时先创建。
 */
@Service
public class AuditIngestion implements StreamConsumer.Handler {

    /** 审计的 Stream 键，即 outbox 主题。 */
    public static final String STREAM = AdminAudit.TOPIC;

    private final AuditLogWriter writer;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Set<LocalDate> partitions = ConcurrentHashMap.newKeySet();

    /**
     * 创建处理逻辑。
     *
     * @param writer 审计写入
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public AuditIngestion(AuditLogWriter writer, TransactionTemplate transactions, Clock clock) {
        this.writer = writer;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void handle(List<StreamMessage> messages) {
        List<AuditEntry> entries = messages.stream()
                .map(message -> AuditMessages.parse(
                        message.field(RedisStreamOutboxPublisher.FIELD_ID),
                        message.field(RedisStreamOutboxPublisher.FIELD_PAYLOAD)))
                .toList();
        entries.stream()
                .map(entry -> entry.occurredAt()
                        .atOffset(ZoneOffset.UTC)
                        .toLocalDate()
                        .withDayOfMonth(1))
                .distinct()
                .filter(month -> !partitions.contains(month))
                .forEach(month -> {
                    writer.ensurePartitions(month);
                    partitions.add(month);
                });
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> entries.forEach(entry -> writer.insert(entry, now)));
    }

    @Override
    public boolean deadLetter(StreamMessage message, RuntimeException error) {
        transactions.executeWithoutResult(status -> writer.deadLetter(
                STREAM,
                message.recordId(),
                message.field(RedisStreamOutboxPublisher.FIELD_ID),
                message.field(RedisStreamOutboxPublisher.FIELD_PAYLOAD),
                String.valueOf(error.getMessage()),
                clock.instant()));
        return true;
    }
}
