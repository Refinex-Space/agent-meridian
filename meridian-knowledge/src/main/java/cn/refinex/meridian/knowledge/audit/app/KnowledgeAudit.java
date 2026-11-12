package cn.refinex.meridian.knowledge.audit.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * knowledge 写操作的审计（ADR-0048 第 6 条的标准字段，ADR-0051）：{@code knowledge_base.created}、{@code document.uploaded}、
 * {@code document.reprocessed}、{@code document.deleted}。操作者、租户与项目取自当前的 {@code PlatformContext}（API Key 或控制台用户）。
 */
@Service
public class KnowledgeAudit {

    /** outbox 主题，与 runtime、control-plane 的审计消息相同。 */
    public static final String TOPIC = "audit";

    private final OutboxWriter outbox;
    private final Clock clock;

    /**
     * 创建审计写入器。
     *
     * @param outbox outbox 写入器
     * @param clock 时钟
     */
    public KnowledgeAudit(OutboxWriter outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * 记录一次写操作。必须在业务事务中、绑定了 {@code PlatformContext} 时调用。
     *
     * @param action 动作，{@code <资源>.<动作>}
     * @param resourceType 资源类型：{@code knowledge_base} 或 {@code document}
     * @param resourceId 资源 ID
     * @param details 补充信息（snake_case 的键）；不含文档内容
     */
    public void record(String action, String resourceType, String resourceId, Map<String, Object> details) {
        PlatformContext context = PlatformContexts.current();
        outbox.append(
                TOPIC,
                new AuditRecord(
                        action,
                        clock.instant(),
                        context.tenantId(),
                        context.projectId(),
                        AuditActor.ofPrincipal(context.actor()),
                        resourceType,
                        resourceId,
                        Collections.unmodifiableMap(new LinkedHashMap<>(details))));
    }

    /**
     * 审计消息的载荷，字段与 control-plane 的审计消费者约定一致（序列化为 snake_case）。
     *
     * @param action 动作
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param actor 操作者
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param details 补充信息
     */
    public record AuditRecord(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            AuditActor actor,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {}
}
