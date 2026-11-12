package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.ToolRetry;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.approval.app.PermissionPolicies;
import cn.refinex.meridian.runtime.approval.app.PermissionPolicies.TurnPermissions;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.delivery.app.DatabaseInbox;
import cn.refinex.meridian.runtime.dispatch.app.TurnClaimer;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.session.domain.RecoveryDecision;
import cn.refinex.meridian.runtime.session.domain.RecoveryPlanner;
import cn.refinex.meridian.runtime.session.domain.RecoverySnapshot;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.ToolCallRecord;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.system.JdbcAgentStateRepository;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.WriteFence;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.tooling.app.DatabaseToolLedger;
import cn.refinex.meridian.runtime.tooling.domain.ToolInvocation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/**
 * 认领之后、调用内核之前的准备（架构设计「一轮执行的生命周期」第 2、3 步）：读取会话、轮次输入与智能体配置；恢复认领时先按
 * 本轮的状态内容写入与工具账本判定恢复方式（{@link RecoveryPlanner}），再交给内核（「崩溃恢复」：先判定，后清理缓存与安装权限，
 * 后两步在 {@code AgentEngine.runTurn} 内完成）；最后写入 {@code session.status_running}。
 *
 * <p>恢复判定可能直接结束本次执行：恢复次数用尽、或有状态未知的 {@code non_idempotent} 调用需要人工确认。
 *
 * <p>方法会阻塞，在虚拟线程上调用，调用线程上要绑定轮次所在项目的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class TurnPreparer {

    /** 恢复说明中每个结果摘要的长度上限（字符），只让模型知道结果的要点，不把大结果再放进上下文。 */
    static final int SUMMARY_MAX_CHARS = 1000;

    /** 读取大对象结果的等待上限。 */
    private static final Duration BLOB_READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TurnPreparer.class);

    private final TurnClaimer claimer;
    private final SessionRepository sessions;
    private final AgentConfigProvider configs;
    private final JdbcAgentStateRepository states;
    private final DatabaseToolLedger ledger;
    private final SessionEventWriter events;
    private final DatabaseInbox inbox;
    private final BlobStorePort blobs;
    private final TurnFinisher finisher;
    private final TurnMetrics metrics;
    private final PermissionPolicies permissions;
    private final PendingActionRepository pendingActions;
    private final TransactionTemplate transactions;

    /**
     * 创建准备用例。
     *
     * @param claimer 认领用例，读取轮次输入
     * @param sessions 会话仓储
     * @param configs 智能体配置来源
     * @param states 智能体状态仓储，查询本轮的内容写入
     * @param ledger 工具账本
     * @param events 事件写入入口
     * @param inbox 收件箱，重新投递 steer
     * @param blobs 大对象存储，读取放在对象存储中的工具结果
     * @param finisher 收尾用例，恢复判定直接结束本次执行时使用
     * @param metrics 指标
     * @param permissions 权限合成
     * @param pendingActions 待处理动作仓储，读取恢复轮要重试的调用
     * @param transactions 事务模板（传播行为 REQUIRED）
     */
    public TurnPreparer(
            TurnClaimer claimer,
            SessionRepository sessions,
            AgentConfigProvider configs,
            JdbcAgentStateRepository states,
            DatabaseToolLedger ledger,
            SessionEventWriter events,
            DatabaseInbox inbox,
            BlobStorePort blobs,
            TurnFinisher finisher,
            TurnMetrics metrics,
            PermissionPolicies permissions,
            PendingActionRepository pendingActions,
            TransactionTemplate transactions) {
        this.claimer = claimer;
        this.sessions = sessions;
        this.configs = configs;
        this.states = states;
        this.ledger = ledger;
        this.events = events;
        this.inbox = inbox;
        this.blobs = blobs;
        this.finisher = finisher;
        this.metrics = metrics;
        this.permissions = permissions;
        this.pendingActions = pendingActions;
        this.transactions = transactions;
    }

    /**
     * 准备一次执行。
     *
     * @param turn 本节点认领的轮次
     * @return 交给内核执行，或已经结束
     * @throws NotFoundException 会话不存在时
     */
    Prepared prepare(ClaimedTurn turn) {
        Session session =
                sessions.find(turn.sessionId()).orElseThrow(() -> new NotFoundException("会话不存在：" + turn.sessionId()));
        TurnInput stored = claimer.input(turn);
        ResolvedAgentSpec spec = configs.agentVersion(session.agentVersionId());
        if (!turn.isRecovery()) {
            return start(turn, session, spec, stored, null, false);
        }
        RecoveryDecision decision = RecoveryPlanner.decide(snapshot(turn, stored));
        log.info(
                "恢复认领 turnId={} epoch={} recoveryAttempt={} decision={}",
                turn.turnId(),
                turn.epoch(),
                turn.recoveryAttempt(),
                decision.getClass().getSimpleName());
        return switch (decision) {
            case RecoveryDecision.Exhausted exhausted ->
                new Prepared.Done(
                        finisher.exhausted(turn, session.agentVersionId(), exhausted), session.agentVersionId());
            case RecoveryDecision.AwaitConfirmation confirmation ->
                new Prepared.Done(
                        finisher.awaitRecoveryConfirmation(turn, session.agentVersionId(), confirmation),
                        session.agentVersionId());
            case RecoveryDecision.Resume resume -> {
                metrics.recovered(resume.origin().mode());
                yield start(turn, session, spec, resume.input(), resume.origin(), resume.redeliverSteer());
            }
        };
    }

    private Prepared start(
            ClaimedTurn turn,
            Session session,
            ResolvedAgentSpec spec,
            TurnInput input,
            RecoveryOrigin origin,
            boolean redeliverSteer) {
        List<EngineEvent> startEvents = new ArrayList<>();
        startEvents.add(new SessionEvent.StatusRunning());
        if (!turn.isRecovery() && input instanceof TurnInput.NewMessages messages) {
            startEvents.add(new SessionEvent.MessageConsumed(
                    messages.messages().stream().map(InboundMessage::eventId).toList()));
        }
        AppendResult appended = transactions.execute(status -> {
            AppendResult result = events.append(new EventBatch(
                    turn.sessionId(),
                    turn.threadId(),
                    turn.turnId(),
                    WriteFence.lease(turn.turnId(), turn.epoch()),
                    startEvents));
            if (result instanceof AppendResult.LeaseLost) {
                status.setRollbackOnly();
            } else if (redeliverSteer) {
                inbox.redeliverSteer(turn.turnId(), origin.failedEpoch());
            }
            return result;
        });
        if (appended instanceof AppendResult.LeaseLost) {
            return new Prepared.Done(new TurnResult.Abandoned(turn.turnId(), "开始执行时租约已失去"), session.agentVersionId());
        }
        TurnPermissions synthesized =
                permissions.forTurn(session.id(), session.interaction(), spec, session.customTools());
        TurnContext context = new TurnContext(
                platformContext(turn, session),
                session.endUserId(),
                session.agentVersionId(),
                session.environmentId(),
                session.environmentRevision(),
                synthesized.main(),
                synthesized.subagents(),
                session.customTools(),
                origin,
                turn.interruptRequestedAt() != null);
        return new Prepared.Ready(context, input, session.agentVersionId(), retries(turn));
    }

    /**
     * 恢复确认选择 {@code retry} 的调用，只在恢复轮第一次执行时返回：重试已发起后再被恢复认领时，账本里有它的记录，按崩溃恢复的
     * 规则处理（成功的写进恢复说明，状态未知的 {@code non_idempotent} 调用再次请求确认），不自动再执行。
     */
    private List<ToolRetry> retries(ClaimedTurn turn) {
        if (turn.isRecovery()) {
            return List.of();
        }
        return pendingActions.listRetriesConsumedBy(turn.turnId()).stream()
                .map(action -> new ToolRetry(
                        action.action().toolUseId(),
                        action.action().toolName(),
                        action.action().input(),
                        action.idempotencyKey()))
                .toList();
    }

    private RecoverySnapshot snapshot(ClaimedTurn turn, TurnInput stored) {
        // 子线程的工具调用记在父轮次下，但不是父线程的调用：父线程只看到 spawn_subagent 本身，子线程不会被恢复（ADR-0040）
        List<ToolInvocation> invocations = ledger.listByTurn(turn.turnId()).stream()
                .filter(invocation -> invocation.threadId().equals(turn.threadId()))
                .filter(invocation -> invocation.leaseEpoch() < turn.epoch())
                .toList();
        Map<String, String> summaries = invocations.stream().anyMatch(i -> i.status() == Status.SUCCEEDED)
                ? summariesFromEvents(turn)
                : Map.of();
        List<ToolCallRecord> calls = invocations.stream()
                .map(invocation -> new ToolCallRecord(
                        invocation.id(),
                        invocation.toolUseId(),
                        invocation.toolName(),
                        invocation.sideEffect(),
                        invocation.status(),
                        invocation.leaseEpoch(),
                        invocation.idempotencyKey(),
                        readInput(invocation.inputJson()),
                        invocation.status() == Status.SUCCEEDED ? summary(invocation, summaries) : null))
                .toList();
        return new RecoverySnapshot(
                stored,
                turn.epoch(),
                turn.recoveryAttempt(),
                states.contentEpochOfTurn(turn.threadId(), turn.turnId()),
                calls);
    }

    /** 本轮已持久化的工具结果事件中的结果文本：工具调用 ID → 文本。 */
    private Map<String, String> summariesFromEvents(ClaimedTurn turn) {
        Map<String, String> summaries = new HashMap<>();
        List<StoredEvent> results = events.listByTurn(
                turn.sessionId(), turn.turnId(), List.of(EventType.AGENT_TOOL_RESULT, EventType.AGENT_MCP_TOOL_RESULT));
        for (StoredEvent stored : results) {
            if (!turn.threadId().equals(stored.threadId())) {
                continue;
            }
            switch (EngineJson.readEvent(stored.type(), stored.data())) {
                case AgentOutputEvent.ToolResult result ->
                    summaries.put(result.toolUseId(), text(result.content(), result.contentRef()));
                case AgentOutputEvent.McpToolResult result ->
                    summaries.put(result.toolUseId(), text(result.content(), result.contentRef()));
                default -> {
                    // 只查询了两种工具结果事件
                }
            }
        }
        return summaries;
    }

    /** 成功调用的结果摘要：优先取工具结果事件，其次取账本中的结果引用；都没有时为空。 */
    private String summary(ToolInvocation invocation, Map<String, String> fromEvents) {
        String text = fromEvents.get(invocation.toolUseId());
        if (text == null && invocation.resultRef() != null) {
            text = readBlob(invocation.resultRef());
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() <= SUMMARY_MAX_CHARS ? text : text.substring(0, SUMMARY_MAX_CHARS) + "…";
    }

    private String text(List<MessageContent> content, BlobRef contentRef) {
        if (contentRef != null) {
            return readBlob(contentRef);
        }
        return content.stream()
                .filter(MessageContent.Text.class::isInstance)
                .map(block -> ((MessageContent.Text) block).text())
                .collect(Collectors.joining("\n"));
    }

    private String readBlob(BlobRef ref) {
        try {
            byte[] content = blobs.get(ref)
                    .contextWrite(PlatformContexts.write(PlatformContexts.current()))
                    .block(BLOB_READ_TIMEOUT);
            return content == null ? null : new String(content, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 摘要只是帮助模型理解，读不到时恢复说明里不带摘要
            log.warn("读取工具结果失败，恢复说明不带结果摘要 key={}", ref.key(), e);
            return null;
        }
    }

    private static Map<String, Object> readInput(String json) {
        return json == null
                ? Map.of()
                : JsonMappers.lenient().readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private static PlatformContext platformContext(ClaimedTurn turn, Session session) {
        return PlatformContext.builder()
                .tenantId(turn.tenantId())
                .projectId(turn.projectId())
                .sessionId(turn.sessionId())
                .threadId(turn.threadId())
                .turnId(turn.turnId())
                .leaseEpoch(turn.epoch())
                .interaction(session.interaction())
                .endUserId(session.endUserId())
                // 会话中的模型调用计入创建它的 API Key 的预算与用量（ADR-0048）
                .apiKeyId(session.apiKeyId())
                .build();
    }
}
