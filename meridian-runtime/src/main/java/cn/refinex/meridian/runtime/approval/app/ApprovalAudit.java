package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.ConclusionOrigin;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 权限判定与待处理动作回应的审计（架构设计「跨服务数据流」：outbox → Redis Stream {@code audit} → control-plane 审计消费者）。
 * 消息与业务数据在同一事务中写入，标准字段见 ADR-0048 第 6 条。
 *
 * <ul>
 *   <li>{@code permission.decided}：每个工具调用的权限判定结果。放行、经审批放行与拒绝随工具结果事件写入（{@link #append}）；
 *       需要确认（ask）在产生待处理动作时写入；无人值守会话的自动拒绝在生成恢复轮时写入。
 *   <li>{@code pending_action.concluded}：每个待处理动作的结论，来源是调用方回应、超时、中断或自动拒绝。
 * </ul>
 *
 * <p>方法会阻塞，调用线程上要绑定轮次或请求所在项目的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class ApprovalAudit {

    /** 审计的 outbox 主题（架构设计「跨服务数据流」）。 */
    public static final String TOPIC = "audit";

    /** 权限判定的审计动作。 */
    public static final String PERMISSION_DECIDED = "permission.decided";

    /** 待处理动作结论的审计动作。 */
    public static final String ACTION_CONCLUDED = "pending_action.concluded";

    /** 平台自己做出的判定与结论的操作者（ADR-0048 第 6 条）。 */
    static final AuditActor RUNTIME = AuditActor.service("runtime");

    /** 权限判定的资源类型：工具调用，资源 ID 是工具调用 ID。 */
    static final String TOOL_CALL = "tool_call";

    private final SessionEventWriter events;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建审计。
     *
     * @param events 事件写入入口
     * @param outbox outbox 写入
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param clock 时钟
     */
    public ApprovalAudit(
            SessionEventWriter events, OutboxWriter outbox, TransactionTemplate transactions, Clock clock) {
        this.events = events;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 写入一批内核事件，并在同一事务中为其中的工具结果事件写 {@code permission.decided}（结果的 {@code permission} 为
     * {@code allowed}、{@code approved} 或 {@code denied}）。租约条件不满足时什么都不写。
     *
     * @param batch 事件批次
     * @return 写入结果
     */
    public AppendResult append(EventBatch batch) {
        return transactions.execute(status -> {
            AppendResult result = events.append(batch);
            if (result instanceof AppendResult.Appended) {
                for (EngineEvent event : batch.events()) {
                    switch (event) {
                        case AgentOutputEvent.ToolResult r ->
                            permission(
                                    batch,
                                    r.toolUseId(),
                                    r.name(),
                                    r.permission().value(),
                                    "engine");
                        case AgentOutputEvent.McpToolResult r ->
                            permission(
                                    batch,
                                    r.toolUseId(),
                                    r.name(),
                                    r.permission().value(),
                                    "engine");
                        default -> {
                            // 其他事件不是权限判定
                        }
                    }
                }
            }
            return result;
        });
    }

    /**
     * 记录"需要确认"的权限判定：产生 {@code tool_confirmation} 或 {@code plan_exit} 待处理动作时调用，必须在调用方的事务中。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param action 待处理动作
     */
    public void asked(String sessionId, String threadId, String turnId, PendingAction action) {
        write(new PermissionDecided(
                PERMISSION_DECIDED,
                clock.instant(),
                tenantId(),
                projectId(),
                RUNTIME,
                TOOL_CALL,
                action.toolUseId(),
                sessionId,
                threadId,
                turnId,
                action.toolUseId(),
                action.toolName(),
                "ask",
                "engine"));
    }

    /**
     * 记录无人值守会话的自动拒绝：内核以 {@code PERMISSION_ASKING} 结束而会话没有人能审批。必须在调用方的事务中调用。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param action 被自动拒绝的调用
     */
    public void autoDenied(String sessionId, String threadId, String turnId, PendingAction action) {
        write(new PermissionDecided(
                PERMISSION_DECIDED,
                clock.instant(),
                tenantId(),
                projectId(),
                RUNTIME,
                TOOL_CALL,
                action.toolUseId(),
                sessionId,
                threadId,
                turnId,
                action.toolUseId(),
                action.toolName(),
                "denied",
                ConclusionOrigin.UNATTENDED.name().toLowerCase(Locale.ROOT)));
    }

    /**
     * 记录一个待处理动作的结论。必须在调用方的事务中调用。
     *
     * @param action 动作
     * @param conclusion 结论
     * @param decidedBy 做出决定的主体；平台生成的结论为空
     */
    public void concluded(StoredPendingAction action, ActionConclusion conclusion, String decidedBy) {
        write(new ActionConcluded(
                ACTION_CONCLUDED,
                clock.instant(),
                tenantId(),
                projectId(),
                decidedBy == null ? RUNTIME : AuditActor.ofPrincipal(decidedBy),
                "pending_action",
                action.id(),
                action.sessionId(),
                action.threadId(),
                action.turnId(),
                action.id(),
                action.type().value(),
                action.toolUseId(),
                action.toolName(),
                conclusion.origin().name().toLowerCase(Locale.ROOT),
                outcome(action.type(), conclusion),
                conclusion.remember() == null ? null : conclusion.remember().value(),
                decidedBy));
    }

    private void permission(EventBatch batch, String toolUseId, String toolName, String decision, String source) {
        write(new PermissionDecided(
                PERMISSION_DECIDED,
                clock.instant(),
                tenantId(),
                projectId(),
                RUNTIME,
                TOOL_CALL,
                toolUseId,
                batch.sessionId(),
                batch.threadId(),
                batch.turnId(),
                toolUseId,
                toolName,
                decision,
                source));
    }

    private void write(Object payload) {
        outbox.append(TOPIC, payload);
    }

    private static String outcome(PendingAction.Type type, ActionConclusion conclusion) {
        return switch (type) {
            case TOOL_CONFIRMATION, PLAN_EXIT -> conclusion.isApproved() ? "approved" : "denied";
            case CUSTOM_TOOL_RESULT -> Boolean.TRUE.equals(conclusion.error()) ? "error" : "result";
            case RECOVERY_CONFIRMATION -> conclusion.decision().value();
        };
    }

    private static String tenantId() {
        return PlatformContexts.current().tenantId();
    }

    private static String projectId() {
        PlatformContext context = PlatformContexts.current();
        return context.projectId();
    }

    /**
     * 一次权限判定的审计记录（主题 {@code audit}）。
     *
     * @param action 固定为 {@code permission.decided}
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param actor 操作者：runtime 的权限引擎或无人值守的自动拒绝
     * @param resourceType 资源类型 {@code tool_call}
     * @param resourceId 工具调用 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param toolUseId 工具调用 ID
     * @param toolName 工具名
     * @param decision 判定结果：{@code allowed}、{@code approved}、{@code denied}、{@code ask}
     * @param source 判定来源：{@code engine}（权限引擎与审批）或 {@code unattended}（平台自动拒绝）
     */
    record PermissionDecided(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            AuditActor actor,
            String resourceType,
            String resourceId,
            String sessionId,
            String threadId,
            String turnId,
            String toolUseId,
            String toolName,
            String decision,
            String source) {}

    /**
     * 一个待处理动作结论的审计记录（主题 {@code audit}）。
     *
     * @param action 固定为 {@code pending_action.concluded}
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param actor 操作者：调用方回应时是 API Key 或控制台用户，超时、中断、自动拒绝时是 runtime
     * @param resourceType 资源类型 {@code pending_action}
     * @param resourceId 动作 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 产生动作的轮次
     * @param actionId 动作 ID
     * @param actionType 动作类型
     * @param toolUseId 相关工具调用 ID
     * @param toolName 相关工具名
     * @param origin 结论来源：{@code caller}、{@code timeout}、{@code interrupt}、{@code unattended}
     * @param outcome 结论：{@code approved}、{@code denied}、{@code result}、{@code error}、{@code retry}、{@code skip}
     * @param rememberScope "记住此决定"的作用域；不是确认类动作时为空
     * @param decidedBy 做出决定的主体；平台生成的结论为空
     */
    record ActionConcluded(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            AuditActor actor,
            String resourceType,
            String resourceId,
            String sessionId,
            String threadId,
            String turnId,
            String actionId,
            String actionType,
            String toolUseId,
            String toolName,
            String origin,
            String outcome,
            String rememberScope,
            String decidedBy) {}
}
