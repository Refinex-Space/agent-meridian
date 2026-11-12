package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RetryStatus;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.approval.app.PendingActionResolver;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.ConclusionOrigin;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.delivery.app.DatabaseInbox;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.app.TransitionResult;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.app.TurnTransitions;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.modelrouting.infra.system.TurnUsageRepository;
import cn.refinex.meridian.runtime.session.domain.RecoveryDecision;
import cn.refinex.meridian.runtime.session.domain.ToolCallRecord;
import cn.refinex.meridian.runtime.session.domain.TurnEnding;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.WriteFence;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 轮次的结束：内核给出终态事件后（或恢复认领时不再执行），在一个事务里完成全部收尾（架构设计「一轮执行的生命周期」）。
 *
 * <ul>
 *   <li>{@code session.status_idle}：结束轮次、租户并发计数减一、写终态事件（会话与线程随之进入 {@code idle}）、写 outbox；
 *       收件箱中有待投递的消息时合并成下一轮。
 *   <li>{@code session.status_requires_action}：轮次进入 {@code awaiting_action}，记录待处理动作（截止时间按配置补上），写终态事件
 *       与 outbox；收件箱中的消息保留，等恢复轮结束后再成为下一轮（架构设计「消息投递」）。需要确认的调用写一条
 *       {@code permission.decided(ask)} 审计。确认轮再次停在 {@code TOOL_SUSPENDED} 时，已回应过的自定义工具由平台直接交出结论。
 *   <li>无人值守会话仍以 {@code PERMISSION_ASKING} 结束（例如工具自检返回安全类 ask）：不产生待处理动作，对全部待确认调用自动
 *       生成拒绝结果，本轮以 {@code tools_denied} 结束并立即入队 {@code confirmations} 恢复轮（架构设计「待处理动作」中的
 *       "无人值守会话"）；收件箱中的消息留到恢复轮结束。
 *   <li>{@code session.status_rescheduled}：按原因置回队列（可重试错误加一次恢复次数，节点下线不加），置回队列的用例自己写这个事件。
 * </ul>
 * 结束轮次的更新以本节点的租约为条件，条件就是 fencing，同一事务里的事件不再附加租约条件；租约已失去时什么都不写，返回
 * {@link TurnResult.Abandoned}。结束与合并收件箱的事务先取线程的咨询锁（{@link ThreadLockRepository}），与 API 写入消息的事务串行；
 * 加锁顺序在此之后仍是"轮次、租户并发计数、会话"（ADR-0027 第 3 条，ADR-0028）。
 *
 * <p>方法会阻塞，在虚拟线程上调用；调用线程上要绑定轮次所在项目的 {@code PlatformContext}（收件箱、待处理动作是项目级仓储）。
 * 线程安全：无状态。
 */
@Service
public class TurnFinisher {

    /** 恢复次数用尽时 {@code session.error} 的错误码，格式见 engine-spi 的 {@code SessionEvent.Error}。 */
    public static final String RECOVERY_EXHAUSTED_CODE = "turn.recovery_exhausted";

    /** 写入 outbox 的审计主题（架构设计「跨服务数据流」）；消息格式由 T16 定稿。 */
    static final String AUDIT_TOPIC = "audit";

    private static final Logger log = LoggerFactory.getLogger(TurnFinisher.class);

    private final TurnTransitions transitions;
    private final TurnEnqueuer enqueuer;
    private final SessionEventWriter events;
    private final DatabaseInbox inbox;
    private final PendingActionRepository pendingActions;
    private final PendingActionResolver resolver;
    private final ApprovalAudit approvalAudit;
    private final ThreadLockRepository threadLocks;
    private final OutboxWriter outbox;
    private final TurnUsageRepository turnUsage;
    private final TransactionTemplate transactions;
    private final WorkerProperties properties;
    private final TurnMetrics metrics;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建收尾用例。
     *
     * @param transitions 轮次状态迁移
     * @param enqueuer 入队，合并收件箱时插入下一轮
     * @param events 事件写入入口
     * @param inbox 收件箱
     * @param pendingActions 待处理动作仓储
     * @param resolver 待处理动作的结论与恢复轮
     * @param approvalAudit 权限判定与动作结论的审计
     * @param threadLocks 线程咨询锁
     * @param outbox outbox 写入
     * @param turnUsage 每轮的用量累计，结束时写 {@code session.usage}（ADR-0050）
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param properties 执行配置，取待处理动作的截止时长
     * @param metrics 指标
     * @param nodeId 本节点 ID，写入审计记录
     * @param clock 时钟
     */
    public TurnFinisher(
            TurnTransitions transitions,
            TurnEnqueuer enqueuer,
            SessionEventWriter events,
            DatabaseInbox inbox,
            PendingActionRepository pendingActions,
            PendingActionResolver resolver,
            ApprovalAudit approvalAudit,
            ThreadLockRepository threadLocks,
            OutboxWriter outbox,
            TurnUsageRepository turnUsage,
            TransactionTemplate transactions,
            WorkerProperties properties,
            TurnMetrics metrics,
            NodeId nodeId,
            Clock clock) {
        this.transitions = transitions;
        this.enqueuer = enqueuer;
        this.events = events;
        this.inbox = inbox;
        this.pendingActions = pendingActions;
        this.resolver = resolver;
        this.approvalAudit = approvalAudit;
        this.threadLocks = threadLocks;
        this.outbox = outbox;
        this.turnUsage = turnUsage;
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    /**
     * 按内核给出的终态事件结束本次执行。
     *
     * @param turn 本节点认领的轮次
     * @param agentVersionId 会话绑定的智能体版本，写入审计记录
     * @param interaction 会话的值守方式
     * @param terminal 终态事件
     * @return 结果
     */
    public TurnResult finish(ClaimedTurn turn, String agentVersionId, Interaction interaction, SessionEvent terminal) {
        return switch (terminal) {
            case SessionEvent.StatusIdle idle -> finishIdle(turn, agentVersionId, idle.stopReason(), List.of(idle));
            case SessionEvent.StatusRequiresAction requires ->
                interaction == Interaction.UNATTENDED
                                && requires.actions().stream().allMatch(TurnFinisher::confirmation)
                        ? autoDeny(turn, agentVersionId, requires.actions())
                        : awaitAction(turn, agentVersionId, interaction, requires.actions(), Map.of());
            case SessionEvent.StatusRescheduled rescheduled ->
                requeue(turn, TurnEnding.requeueReasonOf(rescheduled.reason()));
            default ->
                throw new IllegalArgumentException("不是终态事件：" + terminal.type().value());
        };
    }

    /**
     * 置回队列（可重试错误、节点下线），{@code session.status_rescheduled} 由置回队列的用例写入。
     *
     * @param turn 本节点认领的轮次
     * @param reason 原因
     * @return 结果
     */
    public TurnResult requeue(ClaimedTurn turn, RequeueReason reason) {
        TransitionResult result = transitions.requeue(turn, reason);
        if (result instanceof TransitionResult.LeaseLost) {
            return abandoned(turn, "置回队列时租约已失去");
        }
        metrics.ended("queued", null);
        return new TurnResult.Requeued(turn.turnId(), reason);
    }

    /**
     * 恢复次数用尽：不再执行，写 {@code session.error}（{@code exhausted}）与 {@code session.status_idle}（{@code error}），
     * 轮次以 {@code failed} 结束（架构设计「崩溃恢复」第 5 条）。需要时先重新投递失败那次执行消费的 steer。
     *
     * @param turn 本节点认领的轮次
     * @param agentVersionId 会话绑定的智能体版本
     * @param decision 判定结论
     * @return 结果
     */
    public TurnResult exhausted(ClaimedTurn turn, String agentVersionId, RecoveryDecision.Exhausted decision) {
        log.warn(
                "轮次恢复次数已用尽，以 error 结束 turnId={} recoveryAttempt={} failedEpoch={}",
                turn.turnId(),
                decision.recoveryAttempt(),
                decision.failedEpoch());
        List<EngineEvent> terminal = List.of(
                new SessionEvent.Error(RECOVERY_EXHAUSTED_CODE, "本轮多次执行失败，已停止", RetryStatus.EXHAUSTED),
                new SessionEvent.StatusIdle(StopReason.ERROR));
        return transactions.execute(status -> {
            threadLocks.lock(turn.threadId());
            if (decision.redeliverSteer()) {
                inbox.redeliverSteer(turn.turnId(), decision.failedEpoch());
            }
            TurnResult result = finishIdleInTransaction(turn, agentVersionId, StopReason.ERROR, terminal);
            if (result instanceof TurnResult.Abandoned) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    /**
     * 恢复时发现状态未知的 {@code non_idempotent} 调用：为每个调用创建 {@code recovery_confirmation} 待处理动作，会话转为
     * {@code requires_action}，本轮不执行（架构设计「崩溃恢复」第 4 条）。动作的 {@code payload} 带上账本记录、幂等键与恢复方式，
     * 回应后（T08）据此生成恢复轮。
     *
     * @param turn 本节点认领的轮次
     * @param agentVersionId 会话绑定的智能体版本
     * @param decision 判定结论
     * @return 结果
     */
    public TurnResult awaitRecoveryConfirmation(
            ClaimedTurn turn, String agentVersionId, RecoveryDecision.AwaitConfirmation decision) {
        List<PendingAction> actions = new ArrayList<>();
        Map<String, Map<String, Object>> extras = new LinkedHashMap<>();
        // 恢复说明随每个动作保存，回应后在它上面标出重试或跳过，生成恢复轮（ResumptionPlanner）
        Object noteTree = JsonMappers.lenient().readValue(EngineJson.write(decision.note()), Object.class);
        for (ToolCallRecord call : decision.unknownCalls()) {
            PendingAction action = new PendingAction(
                    Ids.newId(IdPrefix.PENDING_ACTION),
                    PendingAction.Type.RECOVERY_CONFIRMATION,
                    call.toolUseId(),
                    call.toolName(),
                    call.input(),
                    null);
            actions.add(action);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("invocation_id", call.invocationId());
            extra.put("idempotency_key", call.idempotencyKey());
            extra.put("side_effect", call.sideEffect().value());
            extra.put("failed_epoch", decision.failedEpoch());
            extra.put("recovery_mode", decision.origin().mode().name().toLowerCase(Locale.ROOT));
            extra.put("recovery_note", noteTree);
            extras.put(action.id(), extra);
        }
        log.info(
                "恢复时有状态未知的 non_idempotent 调用，等待人工确认 turnId={} calls={}",
                turn.turnId(),
                decision.unknownCalls().size());
        return transactions.execute(status -> {
            threadLocks.lock(turn.threadId());
            if (decision.redeliverSteer()) {
                inbox.redeliverSteer(turn.turnId(), decision.failedEpoch());
            }
            TurnResult result = awaitActionInTransaction(turn, agentVersionId, Interaction.ATTENDED, actions, extras);
            if (result instanceof TurnResult.Abandoned) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private TurnResult finishIdle(
            ClaimedTurn turn, String agentVersionId, StopReason stopReason, List<EngineEvent> terminal) {
        return transactions.execute(status -> {
            threadLocks.lock(turn.threadId());
            TurnResult result = finishIdleInTransaction(turn, agentVersionId, stopReason, terminal);
            if (result instanceof TurnResult.Abandoned) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private TurnResult finishIdleInTransaction(
            ClaimedTurn turn, String agentVersionId, StopReason stopReason, List<EngineEvent> terminal) {
        TurnOutcome outcome = TurnEnding.outcomeOf(stopReason);
        if (transitions.finish(turn, outcome, stopReason) instanceof TransitionResult.LeaseLost) {
            return abandoned(turn, "结束轮次时租约已失去");
        }
        // 先入队再写事件（ADR-0027 第 3 条的锁顺序）
        String nextTurnId = mergeInbox(turn);
        events.append(new EventBatch(
                turn.sessionId(), turn.threadId(), turn.turnId(), WriteFence.none(), withUsage(turn, terminal)));
        audit(turn, agentVersionId, outcome, stopReason, nextTurnId);
        metrics.ended(outcome.value(), stopReason);
        log.info(
                "轮次已结束 turnId={} outcome={} stopReason={} nextTurnId={}",
                turn.turnId(),
                outcome.value(),
                stopReason.value(),
                nextTurnId);
        return new TurnResult.Finished(turn.turnId(), outcome, stopReason);
    }

    private TurnResult awaitAction(
            ClaimedTurn turn,
            String agentVersionId,
            Interaction interaction,
            List<PendingAction> actions,
            Map<String, Map<String, Object>> extras) {
        return transactions.execute(status -> {
            threadLocks.lock(turn.threadId());
            TurnResult result = awaitActionInTransaction(turn, agentVersionId, interaction, actions, extras);
            if (result instanceof TurnResult.Abandoned) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private TurnResult awaitActionInTransaction(
            ClaimedTurn turn,
            String agentVersionId,
            Interaction interaction,
            List<PendingAction> actions,
            Map<String, Map<String, Object>> extras) {
        if (transitions.finish(turn, TurnOutcome.AWAITING_ACTION, null) instanceof TransitionResult.LeaseLost) {
            return abandoned(turn, "等待回应时租约已失去");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.pendingActionTimeout());
        Map<String, ActionConclusion> carried = pendingActions.findUnconsumedCustomResults(
                turn.threadId(),
                actions.stream()
                        .filter(a -> a.type() == PendingAction.Type.CUSTOM_TOOL_RESULT)
                        .map(PendingAction::toolUseId)
                        .toList());
        List<PendingAction> withDeadline = new ArrayList<>(actions.size());
        boolean concluded = false;
        for (PendingAction action : actions) {
            PendingAction pending = action.expiresAt() == null ? action.withExpiresAt(expiresAt) : action;
            Map<String, Object> extra = extras.getOrDefault(action.id(), Map.of());
            ActionConclusion known =
                    action.type() == PendingAction.Type.CUSTOM_TOOL_RESULT ? carried.get(action.toolUseId()) : null;
            if (known == null && interaction == Interaction.UNATTENDED && confirmation(action)) {
                known = ActionConclusion.declined(action.type(), ConclusionOrigin.UNATTENDED);
                approvalAudit.autoDenied(turn.sessionId(), turn.threadId(), turn.turnId(), action);
            } else if (known == null && confirmation(action)) {
                approvalAudit.asked(turn.sessionId(), turn.threadId(), turn.turnId(), action);
            }
            if (known == null) {
                pendingActions.insert(turn.sessionId(), turn.turnId(), turn.threadId(), pending, extra, now);
            } else {
                // 已有结论：无人值守的自动拒绝，或确认轮之前已回应、由平台直接交出的自定义工具结果
                pendingActions.insertConcluded(
                        turn.sessionId(), turn.turnId(), turn.threadId(), pending, extra, known, null, now);
                concluded = true;
            }
            withDeadline.add(pending);
        }
        events.append(new EventBatch(
                turn.sessionId(),
                turn.threadId(),
                turn.turnId(),
                WriteFence.none(),
                withUsage(turn, List.of(new SessionEvent.StatusRequiresAction(withDeadline)))));
        audit(turn, agentVersionId, TurnOutcome.AWAITING_ACTION, null, null);
        metrics.ended(TurnOutcome.AWAITING_ACTION.value(), null);
        log.info("轮次等待回应 turnId={} actions={}", turn.turnId(), withDeadline.size());
        if (concluded) {
            resolver.resumeIfComplete(turn.sessionId(), turn.threadId(), turn.turnId());
        }
        return new TurnResult.Finished(turn.turnId(), TurnOutcome.AWAITING_ACTION, null);
    }

    /**
     * 无人值守会话以 {@code PERMISSION_ASKING} 结束：不产生待处理动作，对全部待确认调用自动生成拒绝结果，本轮以
     * {@code tools_denied} 结束并立即入队 {@code confirmations} 恢复轮；收件箱中的消息留到恢复轮结束（架构设计「待处理动作」）。
     */
    private TurnResult autoDeny(ClaimedTurn turn, String agentVersionId, List<PendingAction> actions) {
        return transactions.execute(status -> {
            threadLocks.lock(turn.threadId());
            if (transitions.finish(turn, TurnOutcome.COMPLETED, StopReason.TOOLS_DENIED)
                    instanceof TransitionResult.LeaseLost) {
                status.setRollbackOnly();
                return abandoned(turn, "自动拒绝时租约已失去");
            }
            List<ToolConfirmation> denials = actions.stream()
                    .map(action -> new ToolConfirmation(
                            action.toolUseId(),
                            action.toolName(),
                            action.input(),
                            false,
                            ActionConclusion.declined(action.type(), ConclusionOrigin.UNATTENDED)
                                    .denyReason(),
                            List.of()))
                    .toList();
            // 先入队再写事件（ADR-0027 第 3 条的锁顺序）
            String nextTurnId = resolver.enqueueResumption(
                    turn.sessionId(), turn.threadId(), new TurnInput.Confirmations(denials, null));
            events.append(new EventBatch(
                    turn.sessionId(),
                    turn.threadId(),
                    turn.turnId(),
                    WriteFence.none(),
                    withUsage(turn, List.of(new SessionEvent.StatusIdle(StopReason.TOOLS_DENIED)))));
            actions.forEach(
                    action -> approvalAudit.autoDenied(turn.sessionId(), turn.threadId(), turn.turnId(), action));
            audit(turn, agentVersionId, TurnOutcome.COMPLETED, StopReason.TOOLS_DENIED, nextTurnId);
            metrics.ended(TurnOutcome.COMPLETED.value(), StopReason.TOOLS_DENIED);
            log.info(
                    "无人值守会话请求确认，已自动拒绝并入队恢复轮 turnId={} calls={} nextTurnId={}",
                    turn.turnId(),
                    actions.size(),
                    nextTurnId);
            return new TurnResult.Finished(turn.turnId(), TurnOutcome.COMPLETED, StopReason.TOOLS_DENIED);
        });
    }

    /**
     * 终态事件之前加上 {@code session.usage}（ADR-0050）：本轮按模型的用量、合计与会话累计，读自与账本同一事务累加的
     * {@code rt.turn_usage}。本轮的模型调用此时都已结算（{@code RoutedModel} 在流结束之前结算）。
     */
    private List<EngineEvent> withUsage(ClaimedTurn turn, List<? extends EngineEvent> terminal) {
        List<TurnUsageRepository.ModelTurnUsage> models = turnUsage.byModel(turn.turnId());
        TokenUsage total = models.stream()
                .map(TurnUsageRepository.ModelTurnUsage::usage)
                .reduce(new TokenUsage(0, 0, 0, 0, 0), TokenUsage::plus);
        String topModel = models.stream()
                .max(Comparator.comparingLong((TurnUsageRepository.ModelTurnUsage m) ->
                                m.usage().totalTokens())
                        .thenComparing(TurnUsageRepository.ModelTurnUsage::modelId, Comparator.reverseOrder()))
                .map(TurnUsageRepository.ModelTurnUsage::modelId)
                .orElse(null);
        SessionEvent.Usage usage = new SessionEvent.Usage(
                topModel,
                total,
                turnUsage.session(turn.sessionId()),
                models.stream()
                        .map(m -> new SessionEvent.Usage.ModelUsage(m.modelId(), m.calls(), m.usage()))
                        .toList());
        List<EngineEvent> result = new ArrayList<>(terminal.size() + 1);
        result.add(usage);
        result.addAll(terminal);
        return result;
    }

    /** 是否为需要人工确认的动作（{@code tool_confirmation}、{@code plan_exit}）。 */
    private static boolean confirmation(PendingAction action) {
        return action.type() == PendingAction.Type.TOOL_CONFIRMATION || action.type() == PendingAction.Type.PLAN_EXIT;
    }

    /** 收件箱中有待投递的消息时合并成下一轮；返回下一轮的 ID，没有消息时为空。 */
    private String mergeInbox(ClaimedTurn turn) {
        String nextTurnId = Ids.newId(IdPrefix.TURN);
        List<InboundMessage> pending = inbox.takePending(turn.threadId(), nextTurnId);
        if (pending.isEmpty()) {
            return null;
        }
        NewTurn next = NewTurn.of(
                turn.tenantId(),
                turn.projectId(),
                turn.sessionId(),
                turn.threadId(),
                new TurnInput.NewMessages(pending, null));
        if (enqueuer.enqueue(nextTurnId, next) instanceof EnqueueResult.ThreadBusy) {
            // 线程咨询锁下不会发生：同一线程的入队都先取这把锁
            throw new IllegalStateException("轮次结束时线程仍有未结束的轮次：" + turn.threadId());
        }
        return nextTurnId;
    }

    private void audit(
            ClaimedTurn turn, String agentVersionId, TurnOutcome outcome, StopReason stopReason, String nextTurnId) {
        outbox.append(
                AUDIT_TOPIC,
                new TurnFinishedAudit(
                        "turn.finished",
                        clock.instant(),
                        turn.tenantId(),
                        turn.projectId(),
                        AuditActor.service("runtime"),
                        "turn",
                        turn.turnId(),
                        turn.sessionId(),
                        turn.threadId(),
                        turn.turnId(),
                        agentVersionId,
                        outcome.value(),
                        stopReason == null ? null : stopReason.value(),
                        turn.epoch(),
                        nodeId.value(),
                        nextTurnId));
    }

    private TurnResult abandoned(ClaimedTurn turn, String cause) {
        log.warn("{}，放弃本次执行 turnId={} epoch={}", cause, turn.turnId(), turn.epoch());
        return new TurnResult.Abandoned(turn.turnId(), cause);
    }

    /**
     * 轮次结束的审计记录（outbox 主题 {@code audit}），标准字段见 ADR-0048 第 6 条。
     *
     * @param action 动作，固定为 {@code turn.finished}
     * @param occurredAt 发生时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param actor 操作者：runtime
     * @param resourceType 资源类型 {@code turn}
     * @param resourceId 轮次 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param agentVersionId 智能体版本 ID
     * @param status 轮次状态
     * @param stopReason 停止原因；不以空闲结束时为空
     * @param epoch 结束它的那次执行的 epoch
     * @param nodeId 执行它的节点
     * @param nextTurnId 合并收件箱生成的下一轮；没有时为空
     */
    record TurnFinishedAudit(
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
            String agentVersionId,
            String status,
            String stopReason,
            long epoch,
            String nodeId,
            String nextTurnId) {}
}
