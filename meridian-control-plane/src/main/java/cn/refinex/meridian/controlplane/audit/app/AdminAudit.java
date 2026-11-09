package cn.refinex.meridian.controlplane.audit.app;

import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 管理操作的审计（ADR-0042）：在业务事务中写 outbox 主题 {@value #TOPIC}，由 Relay 发布到 Redis Stream，T16 的审计消费者入库
 * {@code cp.audit_logs}。载荷中不出现 API Key 明文与哈希，只有 ID 与显示前缀。
 */
@Component
public class AdminAudit {

    /** outbox 主题，与 runtime 的审计消息相同。 */
    public static final String TOPIC = "audit";

    private final OutboxWriter outbox;
    private final Clock clock;

    /**
     * 创建审计写入器。
     *
     * @param outbox outbox 写入器
     * @param clock 时钟
     */
    public AdminAudit(OutboxWriter outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * 记录一次管理操作。必须在业务事务中调用。
     *
     * @param action 动作，{@code <资源>.<动作>}，例如 {@code api_key.created}
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；不针对项目时为空
     * @param resourceType 资源类型，例如 {@code api_key}
     * @param resourceId 资源 ID
     * @param details 补充信息（JSON 对象，snake_case）；不含凭据
     */
    public void record(
            String action,
            String actor,
            String tenantId,
            String projectId,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {
        outbox.append(
                TOPIC,
                new AdminAuditRecord(
                        action,
                        clock.instant(),
                        tenantId,
                        projectId,
                        new Actor("console_user", actor),
                        resourceType,
                        resourceId,
                        java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details))));
    }

    /**
     * 管理操作的审计记录（outbox 主题 {@code audit}），格式由 T16 的审计消费者定稿。
     *
     * @param action 动作
     * @param occurredAt 发生时间，审计表按它分区
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；可为空
     * @param actor 操作者
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param details 补充信息
     */
    public record AdminAuditRecord(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            Actor actor,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {}

    /**
     * 操作者。
     *
     * @param type 类型：T14a 只有 {@code console_user}
     * @param subject 主体标识
     */
    public record Actor(String type, String subject) {}
}
