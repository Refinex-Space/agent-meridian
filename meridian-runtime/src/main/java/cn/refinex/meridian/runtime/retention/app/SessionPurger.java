package cn.refinex.meridian.runtime.retention.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository.DeletedSession;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import cn.refinex.meridian.runtime.webhook.app.WebhookDispatcher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已删除会话的清理作业（架构设计「数据保留」：删除会话后 24 小时内级联清理并写审计，ADR-0049；登记事项 D30、D35、D37）。
 *
 * <p>一个会话依次：
 * <ol>
 *   <li>等该会话的 Webhook 投递结束（删除通知要送到），最长 {@link RetentionProperties#purgeWebhookGrace()}，之后不再等。
 *   <li>删除沙箱、带会话标签的残留沙箱、快照、产物与清单，撤销出网授权（{@link SandboxService#deleteSessionSandbox}）。
 *   <li>删除对象存储中工作区的大内容、会话前缀 {@code rt/{tenant}/{session}/} 下的大结果与归档事件。
 *   <li>一个事务：删除工作区条目与会话行（其余表外键级联），写审计 {@code session.purged}。
 * </ol>
 * 每一步都可重复执行：中途失败时会话行还在，下一轮从头再来；最后一步之后会话不再存在（查询返回 404）。多个节点同时清理同一会话时外部删除
 * 是幂等的，最后的事务以 {@code SKIP LOCKED} 锁行，只有一个节点提交。
 *
 * <p>线程安全：无状态；方法会阻塞。
 */
@Service
public class SessionPurger {

    /** 审计的 outbox 主题（ADR-0048）。 */
    static final String AUDIT_TOPIC = "audit";

    private static final Logger log = LoggerFactory.getLogger(SessionPurger.class);

    private final SessionLifecycleRepository sessions;
    private final SandboxService sandboxes;
    private final WebhookDispatcher webhooks;
    private final ObjectStore objects;
    private final OutboxWriter outbox;
    private final RetentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建作业。
     *
     * @param sessions 跨租户的会话仓储
     * @param sandboxes 会话沙箱
     * @param webhooks Webhook 投递器，判断删除通知是否已送出
     * @param objects 对象存储
     * @param outbox outbox 写入，审计用
     * @param properties 配置
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public SessionPurger(
            SessionLifecycleRepository sessions,
            SandboxService sandboxes,
            WebhookDispatcher webhooks,
            ObjectStore objects,
            OutboxWriter outbox,
            RetentionProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.sessions = sessions;
        this.sandboxes = sandboxes;
        this.webhooks = webhooks;
        this.objects = objects;
        this.outbox = outbox;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 扫描一次。单个会话失败只记日志，下次重试。
     *
     * @return 本次清理完成的会话数
     */
    public int purgeOnce() {
        List<DeletedSession> deleted =
                transactions.execute(status -> sessions.deletedSessions(properties.purgeBatchSize()));
        int purged = 0;
        for (DeletedSession session : deleted == null ? List.<DeletedSession>of() : deleted) {
            try {
                if (purge(session)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("清理已删除的会话失败，下次重试 sessionId={}", session.sessionId(), e);
            }
        }
        return purged;
    }

    /**
     * 清理一个已删除的会话。
     *
     * @param session 会话
     * @return 清理完成时为 true；还在等 Webhook 投递或被别的节点处理时为 false
     */
    public boolean purge(DeletedSession session) {
        Instant now = clock.instant();
        boolean graceOver = !session.deletedAt().isAfter(now.minus(properties.purgeWebhookGrace()));
        if (!graceOver && webhooks.hasUnfinished(session.sessionId())) {
            log.debug("会话的 Webhook 投递还没结束，稍后清理 sessionId={}", session.sessionId());
            return false;
        }
        PlatformContext context = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(session.projectId())
                .sessionId(session.sessionId())
                .build();
        return PlatformContexts.call(context, () -> {
            sandboxes.deleteSessionSandbox(session.sessionId());
            List<String> workspaceObjects = transactions.execute(status ->
                    sessions.workspaceObjectKeys(session.tenantId(), session.projectId(), session.sessionId()));
            if (workspaceObjects != null) {
                workspaceObjects.forEach(objects::delete);
            }
            int prefixed =
                    objects.deleteByPrefix(ObjectStoreBlobStore.sessionPrefix(session.tenantId(), session.sessionId()));
            Boolean done = transactions.execute(status -> {
                if (!sessions.lockDeleted(session.sessionId())) {
                    return false;
                }
                int workspace = sessions.deleteWorkspace(session.tenantId(), session.projectId(), session.sessionId());
                sessions.deleteTurnUsage(session.sessionId());
                sessions.deleteSession(session.sessionId());
                outbox.append(
                        AUDIT_TOPIC,
                        new SessionPurgedAudit(
                                "session.purged",
                                clock.instant(),
                                session.tenantId(),
                                session.projectId(),
                                AuditActor.service("runtime"),
                                "session",
                                session.sessionId(),
                                session.sessionId(),
                                session.deletedAt(),
                                workspace,
                                prefixed));
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                log.info("已删除的会话清理完成 sessionId={} deletedAt={}", session.sessionId(), session.deletedAt());
                return true;
            }
            return false;
        });
    }

    /**
     * 会话清理完成的审计记录（outbox 主题 {@code audit}，标准字段见 ADR-0048 第 6 条）。
     *
     * @param action 动作，固定为 {@code session.purged}
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param actor 操作者：runtime
     * @param resourceType 资源类型 {@code session}
     * @param resourceId 会话 ID
     * @param sessionId 会话 ID
     * @param deletedAt 删除请求的时间
     * @param workspaceEntries 删除的工作区条目数
     * @param objects 按会话前缀删除的对象数
     */
    record SessionPurgedAudit(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            AuditActor actor,
            String resourceType,
            String resourceId,
            String sessionId,
            Instant deletedAt,
            int workspaceEntries,
            int objects) {}
}
