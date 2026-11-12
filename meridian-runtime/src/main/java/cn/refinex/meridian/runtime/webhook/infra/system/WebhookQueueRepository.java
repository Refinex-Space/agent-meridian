package cn.refinex.meridian.runtime.webhook.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * Webhook 投递器的跨租户仓储（ADR-0049）：outbox 的认领与删除、投递记录的插入、认领、结果与保留期清理，以及会话清理所需的"是否还有
 * 未完成的投递"。认领都用 {@code FOR UPDATE SKIP LOCKED}，多节点不重复处理。方法在调用方的事务中执行。
 */
@Repository
@CrossTenant(reason = "投递器是后台作业，跨租户扫描 Webhook 的 outbox 与到期的投递记录；租户与项目取自行本身")
public class WebhookQueueRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public WebhookQueueRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 认领 outbox 中最早的一批事件（行锁到事务结束）。
     *
     * @param limit 至多条数
     * @return 事件，按写入时间
     */
    public List<OutboxEvent> claimEvents(int limit) {
        return jdbc().sql("""
                        SELECT event_id, tenant_id, project_id, session_id, event_type, payload
                          FROM rt.webhook_events
                         ORDER BY created_at, event_id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, row) -> new OutboxEvent(
                        rs.getString("event_id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("session_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    /**
     * 删除已扇出的 outbox 事件。
     *
     * @param eventId 事件 ID
     */
    public void deleteEvent(String eventId) {
        jdbc().sql("DELETE FROM rt.webhook_events WHERE event_id = :event_id")
                .param("event_id", eventId)
                .update();
    }

    /**
     * 插入一条待投递的记录，立即到期。
     *
     * @param id 投递记录 ID
     * @param event 事件
     * @param webhookId Webhook ID
     * @param redeliveryOf 手动重投时为原记录 ID；否则为空
     * @param now 当前时间
     * @param deadline 截止时间
     */
    public void insertDelivery(
            String id, OutboxEvent event, String webhookId, String redeliveryOf, Instant now, Instant deadline) {
        jdbc().sql("""
                        INSERT INTO rt.webhook_deliveries (id, tenant_id, project_id, webhook_id, session_id, event_id,
                                                           event_type, payload, status, next_attempt_at, deadline_at,
                                                           redelivery_of, created_at)
                        VALUES (:id, :tenant_id, :project_id, :webhook_id, :session_id, :event_id, :event_type,
                                :payload, 'pending', :now, :deadline, :redelivery_of, :now)
                        """)
                .param("id", id)
                .param("tenant_id", event.tenantId())
                .param("project_id", event.projectId())
                .param("webhook_id", webhookId)
                .param("session_id", event.sessionId())
                .param("event_id", event.eventId())
                .param("event_type", event.eventType())
                .param("payload", event.payload())
                .param("now", JdbcValues.timestamptz(now))
                .param("deadline", JdbcValues.timestamptz(deadline))
                .param("redelivery_of", redeliveryOf)
                .update();
    }

    /**
     * 认领到期的待投递记录：认领期内其他节点不会再取到它们。
     *
     * @param now 当前时间
     * @param claimedUntil 认领截止时间
     * @param limit 至多条数
     * @return 认领到的记录
     */
    public List<DueDelivery> claimDue(Instant now, Instant claimedUntil, int limit) {
        // 截到微秒：库中的精度是微秒，记录结果时按相等比较
        Instant claim = claimedUntil.truncatedTo(ChronoUnit.MICROS);
        return jdbc().sql("""
                        UPDATE rt.webhook_deliveries d SET claimed_until = :claimed_until
                          FROM (SELECT id FROM rt.webhook_deliveries
                                 WHERE status = 'pending' AND next_attempt_at <= :now
                                   AND (claimed_until IS NULL OR claimed_until <= :now)
                                 ORDER BY next_attempt_at
                                 LIMIT :limit
                                   FOR UPDATE SKIP LOCKED) due
                         WHERE d.id = due.id
                        RETURNING d.id, d.tenant_id, d.project_id, d.webhook_id, d.event_id, d.event_type, d.payload,
                                  d.attempts, d.deadline_at
                        """)
                .param("now", JdbcValues.timestamptz(now))
                .param("claimed_until", JdbcValues.timestamptz(claim))
                .param("limit", limit)
                .query((rs, row) -> new DueDelivery(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("webhook_id"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        JdbcValues.instant(rs, "deadline_at"),
                        claim))
                .list();
    }

    /**
     * 记录一次尝试的结果。只在本次认领仍有效（{@code claimed_until} 未变）且仍待投递时生效。
     *
     * @param delivery 认领到的记录
     * @param attempted 是否真的发出了请求（Webhook 已删除或停用时没有发出，不计次数）
     * @param now 当前时间
     * @param responseStatus 收到的 HTTP 状态码；可为空
     * @param error 失败原因；成功时为空
     * @param nextAttemptAt 下一次尝试的时间；为空表示结束（成功或最终失败）
     * @param succeeded 是否成功
     * @return 记录生效时为 true；认领已过期并被别的节点接手时为 false
     */
    public boolean record(
            DueDelivery delivery,
            boolean attempted,
            Instant now,
            Integer responseStatus,
            String error,
            Instant nextAttemptAt,
            boolean succeeded) {
        String status = nextAttemptAt != null ? "pending" : succeeded ? "succeeded" : "failed";
        return jdbc().sql("""
                        UPDATE rt.webhook_deliveries
                           SET status = :status,
                               attempts = attempts + :increment,
                               last_attempt_at = CASE WHEN :increment = 1 THEN :now ELSE last_attempt_at END,
                               last_response_status = :response_status,
                               last_error = :error,
                               next_attempt_at = :next_attempt_at,
                               claimed_until = NULL,
                               completed_at = CASE WHEN :status = 'pending' THEN NULL ELSE :now END
                         WHERE id = :id AND status = 'pending' AND claimed_until = :claimed_until
                        """)
                        .param("status", status)
                        .param("increment", attempted ? 1 : 0)
                        .param("now", JdbcValues.timestamptz(now))
                        .param("response_status", responseStatus)
                        .param("error", error)
                        .param("next_attempt_at", nextAttemptAt == null ? null : JdbcValues.timestamptz(nextAttemptAt))
                        .param("id", delivery.id())
                        .param("claimed_until", JdbcValues.timestamptz(delivery.claimedUntil()))
                        .update()
                == 1;
    }

    /**
     * 会话是否还有未扇出的 outbox 事件或待投递的记录（会话清理据此等待删除通知投递完）。
     *
     * @param sessionId 会话 ID
     * @return 有时为 true
     */
    public boolean hasUnfinished(String sessionId) {
        return jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM rt.webhook_events WHERE session_id = :session_id)
                            OR EXISTS (SELECT 1 FROM rt.webhook_deliveries
                                        WHERE session_id = :session_id AND status = 'pending')
                        """)
                .param("session_id", sessionId)
                .query(Boolean.class)
                .single();
    }

    /**
     * 删除创建早于截止时间、已结束的投递记录（保留期）。
     *
     * @param before 截止时间
     * @param limit 至多条数
     * @return 删除的条数
     */
    public int purgeFinishedBefore(Instant before, int limit) {
        return jdbc().sql("""
                        DELETE FROM rt.webhook_deliveries
                         WHERE id IN (SELECT id FROM rt.webhook_deliveries
                                       WHERE created_at < :before AND status <> 'pending'
                                       LIMIT :limit)
                        """)
                .param("before", JdbcValues.timestamptz(before))
                .param("limit", limit)
                .update();
    }

    /**
     * outbox 中的一个事件。
     *
     * @param eventId 事件 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param eventType 事件类型
     * @param payload 事件信封原文
     */
    public record OutboxEvent(
            String eventId, String tenantId, String projectId, String sessionId, String eventType, String payload) {}

    /**
     * 认领到的一条待投递记录。
     *
     * @param id 投递记录 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param webhookId Webhook ID
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param payload 请求体
     * @param attempts 之前已尝试的次数
     * @param deadline 截止时间
     * @param claimedUntil 本次认领的截止时间，记录结果时作为条件
     */
    public record DueDelivery(
            String id,
            String tenantId,
            String projectId,
            String webhookId,
            String eventId,
            String eventType,
            String payload,
            int attempts,
            Instant deadline,
            Instant claimedUntil) {}
}
