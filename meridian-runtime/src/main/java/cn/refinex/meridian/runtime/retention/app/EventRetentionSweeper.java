package cn.refinex.meridian.runtime.retention.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository.ArchiveState;
import cn.refinex.meridian.runtime.stream.app.EventArchives;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事件保留清理作业（架构设计「数据保留」：会话事件默认保留 365 天，租户可配置，ADR-0049）。
 *
 * <p>逐租户读取保留天数（{@link EventRetentionPolicyPort}），截止时间是当前时间减保留天数。对每个可能有过期事件的会话（创建早于截止
 * 时间，从未清理或上次清理之后有活动）在会话行锁内：删除数据库中早于截止时间的事件；归档文件中有过期事件时剔除它们写成新文件，全部过期
 * 时删除文件；记下已执行到的截止时间。一个租户一次清理有删除时写一条审计 {@code session_events.expired}。读不到某个租户的保留天数时
 * 跳过该租户。
 *
 * <p>线程安全：无状态；方法会阻塞。
 */
@Service
public class EventRetentionSweeper {

    /** 审计的 outbox 主题（ADR-0048）。 */
    static final String AUDIT_TOPIC = "audit";

    private static final Logger log = LoggerFactory.getLogger(EventRetentionSweeper.class);

    private final SessionLifecycleRepository sessions;
    private final EventArchives archives;
    private final EventRetentionPolicyPort policies;
    private final OutboxWriter outbox;
    private final RetentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建作业。
     *
     * @param sessions 跨租户的会话仓储
     * @param archives 归档文件
     * @param policies 租户的保留天数
     * @param outbox outbox 写入，审计用
     * @param properties 配置
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public EventRetentionSweeper(
            SessionLifecycleRepository sessions,
            EventArchives archives,
            EventRetentionPolicyPort policies,
            OutboxWriter outbox,
            RetentionProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.sessions = sessions;
        this.archives = archives;
        this.policies = policies;
        this.outbox = outbox;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 扫描一次全部租户。
     *
     * @return 本次删除的事件数（数据库与归档文件合计）
     */
    public long sweepOnce() {
        List<String> tenants = transactions.execute(status -> sessions.tenantsWithSessions());
        long total = 0;
        for (String tenantId : tenants == null ? List.<String>of() : tenants) {
            int days;
            try {
                days = policies.eventRetentionDays(tenantId);
            } catch (RuntimeException e) {
                log.warn("读不到租户的事件保留天数，跳过 tenantId={}", tenantId, e);
                continue;
            }
            total += sweepTenant(tenantId, days);
        }
        return total;
    }

    /**
     * 清理一个租户。
     *
     * @param tenantId 租户 ID
     * @param days 保留天数
     * @return 删除的事件数
     */
    long sweepTenant(String tenantId, int days) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(days));
        List<String> candidates = transactions.execute(
                status -> sessions.expiryCandidates(tenantId, cutoff, properties.expiryBatchSize()));
        Totals totals = new Totals();
        for (String sessionId : candidates == null ? List.<String>of() : candidates) {
            try {
                expire(sessionId, cutoff, totals);
            } catch (RuntimeException e) {
                log.warn("清理会话的过期事件失败，下次重试 sessionId={}", sessionId, e);
            }
        }
        if (totals.events > 0 || totals.archivesDeleted > 0) {
            transactions.executeWithoutResult(status -> outbox.append(
                    AUDIT_TOPIC,
                    new EventsExpiredAudit(
                            "session_events.expired",
                            clock.instant(),
                            tenantId,
                            null,
                            AuditActor.service("runtime"),
                            "tenant",
                            tenantId,
                            cutoff,
                            days,
                            totals.sessions,
                            totals.events,
                            totals.archivesDeleted,
                            totals.archivesRewritten)));
            log.info(
                    "过期事件已删除 tenantId={} cutoff={} sessions={} events={} archivesDeleted={} archivesRewritten={}",
                    tenantId,
                    cutoff,
                    totals.sessions,
                    totals.events,
                    totals.archivesDeleted,
                    totals.archivesRewritten);
        }
        return totals.events;
    }

    /** 清理一个会话；行被锁住时跳过，下次再试。 */
    private void expire(String sessionId, Instant cutoff, Totals totals) {
        AtomicReference<String> written = new AtomicReference<>();
        AtomicReference<String> superseded = new AtomicReference<>();
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> {
                Optional<ArchiveState> locked = sessions.lockForExpiry(sessionId);
                if (locked.isEmpty()) {
                    return null;
                }
                ArchiveState state = locked.get();
                int fromDatabase = sessions.deleteEventsBefore(sessionId, cutoff);
                String key = state.archiveKey();
                Instant from = state.archiveFrom();
                int fromArchive = 0;
                boolean archiveDeleted = false;
                boolean archiveRewritten = false;
                if (key != null && from != null && from.isBefore(cutoff)) {
                    List<StoredEvent> archived = archives.read(key, state.tenantId(), state.projectId())
                            .orElseThrow(() -> new IllegalStateException("归档文件不存在：" + state.archiveKey()));
                    List<StoredEvent> keep = archived.stream()
                            .filter(event -> !event.createdAt().isBefore(cutoff))
                            .toList();
                    fromArchive = archived.size() - keep.size();
                    superseded.set(key);
                    if (keep.isEmpty()) {
                        key = null;
                        from = null;
                        archiveDeleted = true;
                    } else {
                        key = archives.write(state.tenantId(), sessionId, state.archivedSeq(), keep);
                        written.set(key);
                        from = keep.getFirst().createdAt();
                        archiveRewritten = true;
                    }
                }
                sessions.markExpired(sessionId, cutoff, key, from);
                return new Outcome(fromDatabase + fromArchive, archiveDeleted, archiveRewritten);
            });
        } catch (RuntimeException e) {
            archives.delete(written.get());
            throw e;
        }
        if (outcome == null) {
            return;
        }
        archives.delete(superseded.get());
        if (outcome.events() > 0 || outcome.archiveDeleted()) {
            totals.sessions++;
        }
        totals.events += outcome.events();
        totals.archivesDeleted += outcome.archiveDeleted() ? 1 : 0;
        totals.archivesRewritten += outcome.archiveRewritten() ? 1 : 0;
    }

    /** 一个会话的清理结果。 */
    private record Outcome(int events, boolean archiveDeleted, boolean archiveRewritten) {}

    /** 一个租户的累计。只在作业线程上使用。 */
    private static final class Totals {
        private int sessions;
        private long events;
        private int archivesDeleted;
        private int archivesRewritten;
    }

    /**
     * 事件过期删除的审计记录（outbox 主题 {@code audit}，标准字段见 ADR-0048 第 6 条）：一个租户一次清理一条。
     *
     * @param action 动作，固定为 {@code session_events.expired}
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 为空：一次清理跨该租户的全部项目
     * @param actor 操作者：runtime
     * @param resourceType 资源类型 {@code tenant}
     * @param resourceId 租户 ID
     * @param cutoff 截止时间：早于它的事件被删除
     * @param retentionDays 保留天数
     * @param sessions 有事件被删除的会话数
     * @param events 删除的事件数（数据库与归档文件合计）
     * @param archivesDeleted 整个删除的归档文件数
     * @param archivesRewritten 剔除过期事件后重写的归档文件数
     */
    record EventsExpiredAudit(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            AuditActor actor,
            String resourceType,
            String resourceId,
            Instant cutoff,
            int retentionDays,
            int sessions,
            long events,
            int archivesDeleted,
            int archivesRewritten) {}
}
