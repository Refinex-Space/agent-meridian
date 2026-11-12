package cn.refinex.meridian.controlplane.audit.infra.system;

import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code cp.audit_logs} 的写入：审计消费者是唯一入口（ADR-0048 第 6 条），只插入，按 {@code (id, occurred_at)} 幂等。另有分区的创建与
 * 死信。
 */
@Repository
@CrossTenant(reason = "审计消费者是跨租户的后台作业（架构设计「租户隔离」中 SystemRepository 一行）")
public class AuditLogWriter extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AuditLogWriter(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 写入一条审计；同一 {@code (id, occurred_at)} 已存在时什么都不做。
     *
     * @param entry 审计记录
     * @param recordedAt 入库时间
     * @return 真正插入时为 true
     */
    public boolean insert(AuditEntry entry, Instant recordedAt) {
        return jdbc().sql("""
                        INSERT INTO cp.audit_logs (id, occurred_at, tenant_id, project_id, action, actor_type, actor_subject,
                                                   resource_type, resource_id, session_id, details, recorded_at)
                        VALUES (:id, :occurred_at, :tenant, :project, :action, :actor_type, :actor_subject, :resource_type,
                                :resource_id, :session, :details, :recorded_at)
                        ON CONFLICT (id, occurred_at) DO NOTHING
                        """)
                        .param("id", entry.id())
                        .param("occurred_at", JdbcValues.timestamptz(entry.occurredAt()))
                        .param("tenant", entry.tenantId())
                        .param("project", entry.projectId())
                        .param("action", entry.action())
                        .param("actor_type", entry.actorType())
                        .param("actor_subject", entry.actorSubject())
                        .param("resource_type", entry.resourceType())
                        .param("resource_id", entry.resourceId())
                        .param("session", entry.sessionId())
                        .param("details", JdbcValues.jsonbOf(entry.details()))
                        .param("recorded_at", JdbcValues.timestamptz(recordedAt))
                        .update()
                == 1;
    }

    /**
     * 确保某个 UTC 月份的分区存在（{@code cp.ensure_ledger_partitions}，同时建用量与审计两张表的）。
     *
     * @param month 月份中的任意一天
     * @return 新建的分区数
     */
    public int ensurePartitions(LocalDate month) {
        return jdbc().sql("SELECT cp.ensure_ledger_partitions(:month)")
                .param("month", month)
                .query(Integer.class)
                .single();
    }

    /**
     * 写一条死信。
     *
     * @param stream Stream 的键
     * @param recordId Redis 条目 ID
     * @param messageId outbox 消息 ID；可为空
     * @param payload 载荷原文；可为空
     * @param error 失败原因
     * @param now 当前时间
     */
    public void deadLetter(
            String stream, String recordId, String messageId, String payload, String error, Instant now) {
        jdbc().sql("""
                        INSERT INTO cp.stream_dead_letters (stream, record_id, message_id, payload, error, created_at)
                        VALUES (:stream, :record, :message, :payload, :error, :now)
                        """)
                .param("stream", stream)
                .param("record", recordId)
                .param("message", messageId)
                .param("payload", payload)
                .param("error", error)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }
}
