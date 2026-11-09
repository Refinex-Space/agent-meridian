package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.dispatch.app.NodeChannel;
import cn.refinex.meridian.runtime.dispatch.app.TurnTransitions;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.ActiveTurn;
import cn.refinex.meridian.runtime.session.domain.NewSession;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.SessionErrorCode;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.session.domain.SessionThread;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会话的用例：创建、查询、列表、更新元数据、归档、删除，以及列出线程（公开 API 的会话部分，架构设计「API 设计」「状态机」）。
 *
 * <p>所有方法都在当前项目内执行：仓储从 {@code PlatformContext} 取租户与项目，其他项目的会话一律表现为不存在，即使
 * {@code end_user_id} 相同（架构设计「业务接入示例」）。方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Service
public class SessionService {

    /** 元数据键的长度上限，与规范中 {@code Metadata.propertyNames} 一致。 */
    static final int METADATA_KEY_MAX = 64;

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessions;
    private final ThreadRepository threads;
    private final ThreadLockRepository threadLocks;
    private final PendingActionRepository pendingActions;
    private final TurnTransitions transitions;
    private final NodeChannel nodeChannel;
    private final SessionEventWriter events;
    private final AgentConfigProvider agentConfigs;
    private final ReservedToolNames reservedToolNames;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param sessions 会话仓储
     * @param threads 线程仓储
     * @param threadLocks 线程咨询锁
     * @param pendingActions 待处理动作仓储
     * @param transitions 轮次迁移，终止时取消未结束的轮次
     * @param nodeChannel 节点频道，取消执行中的轮次后通知持有租约的节点立即放弃
     * @param events 事件写入入口
     * @param agentConfigs 智能体与环境配置，创建会话时解析并固化版本
     * @param reservedToolNames 保留工具名，校验自定义工具声明
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param clock 时钟
     */
    public SessionService(
            SessionRepository sessions,
            ThreadRepository threads,
            ThreadLockRepository threadLocks,
            PendingActionRepository pendingActions,
            TurnTransitions transitions,
            NodeChannel nodeChannel,
            SessionEventWriter events,
            AgentConfigProvider agentConfigs,
            ReservedToolNames reservedToolNames,
            TransactionTemplate transactions,
            Clock clock) {
        this.sessions = sessions;
        this.threads = threads;
        this.threadLocks = threadLocks;
        this.pendingActions = pendingActions;
        this.transitions = transitions;
        this.nodeChannel = nodeChannel;
        this.events = events;
        this.agentConfigs = agentConfigs;
        this.reservedToolNames = reservedToolNames;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建一个空闲会话及其主线程：解析智能体版本与环境的当前修订并固化在会话中，之后修改智能体或环境不影响这个会话。
     *
     * @param command 创建请求
     * @return 新会话
     * @throws NotFoundException 智能体或环境不存在时（{@code agent.not_found}、{@code environment.not_found}）
     * @throws ValidationException 元数据的键过长或自定义工具重名时
     */
    public Session create(CreateSessionCommand command) {
        validateMetadata(command.metadata());
        validateCustomTools(command.customTools());
        ResolvedAgentSpec agent = command.agent().startsWith(IdPrefix.AGENT_VERSION.value() + "_")
                ? agentConfigs.agentVersion(command.agent())
                : agentConfigs.currentAgentVersion(command.agent());
        EnvironmentSpec environment = agentConfigs.currentEnvironment(command.environmentId());
        if (!command.customTools().isEmpty()) {
            rejectReservedNames(command.customTools(), reservedToolNames.of(agent, environment));
        }
        String sessionId = Ids.newId(IdPrefix.SESSION);
        NewSession session = new NewSession(
                sessionId,
                agent.agentVersionId(),
                environment.environmentId(),
                environment.revision(),
                command.endUserId(),
                command.interaction(),
                command.endUserAttributes(),
                command.metadata(),
                command.customTools());
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            sessions.insert(session, now);
            threads.insert(NewThread.main(Ids.newId(IdPrefix.THREAD), sessionId, agent.name()), now);
        });
        log.info(
                "会话已创建 sessionId={} agentVersionId={} environmentId={} revision={}",
                sessionId,
                agent.agentVersionId(),
                environment.environmentId(),
                environment.revision());
        return get(sessionId);
    }

    /**
     * 查询当前项目中的会话，包括已删除但尚未清理的。
     *
     * @param sessionId 会话 ID
     * @return 会话
     * @throws NotFoundException 会话不存在或不属于当前项目时（{@code session.not_found}）
     */
    public Session get(String sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> notFound(sessionId));
    }

    /**
     * 列出当前项目中未删除的会话，新创建的在前。
     *
     * @param page 分页参数
     * @param endUserId 只列出该终端用户的会话；可为空
     * @return 一页会话
     */
    public PageResult<Session> list(PageRequest page, String endUserId) {
        return PageResult.of(sessions.list(page, endUserId), page, session -> Cursor.of(session.id()));
    }

    /**
     * 整体替换会话的元数据。
     *
     * @param sessionId 会话 ID
     * @param metadata 新的元数据
     * @return 更新后的会话
     * @throws NotFoundException 会话不存在时
     * @throws ConflictException 会话已终止时（{@code session.terminated}），终止的会话只读
     */
    public Session updateMetadata(String sessionId, Map<String, String> metadata) {
        validateMetadata(metadata);
        transactions.executeWithoutResult(status -> {
            requireWritable(get(sessionId));
            sessions.updateMetadata(sessionId, metadata, clock.instant());
        });
        return get(sessionId);
    }

    /**
     * 归档会话：只有 {@code idle} 或 {@code requires_action} 的会话可以归档，排队与等待回应的轮次随之取消，未回应的待处理动作作废，
     * 写入 {@code session.status_terminated(archived)}（架构设计「状态机」）。已归档的会话原样返回。
     *
     * @param sessionId 会话 ID
     * @return 归档后的会话
     * @throws NotFoundException 会话不存在时
     * @throws ConflictException 会话正在执行时（{@code session.busy}），或已删除时（{@code session.terminated}）
     */
    public Session archive(String sessionId) {
        Session archived = transactions.execute(status -> {
            Session session = get(sessionId);
            if (session.isDeleted()) {
                throw terminated(sessionId);
            }
            if (session.isTerminated()) {
                return session;
            }
            lockMainThread(sessionId);
            List<ActiveTurn> active = transitions.lockActive(sessionId);
            // 锁住轮次之后再读状态：此后认领、回收与结束都要等本事务提交，状态不会再变
            SessionStatus current = get(sessionId).status();
            boolean running = active.stream().anyMatch(ActiveTurn::running);
            if (running || (current != SessionStatus.IDLE && current != SessionStatus.REQUIRES_ACTION)) {
                throw new ConflictException(SessionErrorCode.SESSION_BUSY, "会话正在执行，不能归档；需要停止时先中断，或直接删除会话");
            }
            terminate(sessionId, SessionEvent.TerminationReason.ARCHIVED);
            return null;
        });
        if (archived != null) {
            return archived;
        }
        log.info("会话已归档 sessionId={}", sessionId);
        return get(sessionId);
    }

    /**
     * 删除会话：取消排队、执行中与等待回应的轮次，未回应的待处理动作作废，写入 {@code session.status_terminated(deleted)}，
     * 记录删除时间，并删除全部线程（含子线程）的智能体状态（架构设计「状态机」「数据保留」「子智能体」）。其余数据的清理在 24 小时内
     * 由后台作业完成（T17a）。重复删除原样返回。
     *
     * @param sessionId 会话 ID
     * @return 删除后的会话
     * @throws NotFoundException 会话不存在时
     */
    public Session delete(String sessionId) {
        transactions.executeWithoutResult(status -> {
            Session session = get(sessionId);
            if (session.isDeleted()) {
                return;
            }
            lockMainThread(sessionId);
            if (session.isTerminated()) {
                cancelActive(sessionId);
                pendingActions.expirePending(sessionId, clock.instant());
            } else {
                terminate(sessionId, SessionEvent.TerminationReason.DELETED);
            }
            sessions.markDeleted(sessionId, clock.instant());
            // 执行中的轮次已在本事务中取消，带租约的状态写入会被拒绝，删除之后不会再写入
            int states = threads.deleteAgentStates(sessionId);
            log.info("会话已删除 sessionId={} agentStates={}", sessionId, states);
        });
        return get(sessionId);
    }

    /**
     * 按创建顺序列出会话的线程，主线程在最前。
     *
     * @param sessionId 会话 ID
     * @param page 分页参数
     * @return 一页线程
     * @throws NotFoundException 会话不存在时
     */
    public PageResult<SessionThread> threads(String sessionId, PageRequest page) {
        get(sessionId);
        return PageResult.of(threads.page(sessionId, page), page, thread -> Cursor.of(thread.id()));
    }

    /**
     * 读取会话的主线程。
     *
     * @param sessionId 会话 ID
     * @return 主线程
     * @throws NotFoundException 会话不存在时（{@code session.not_found}）
     */
    public SessionThread mainThread(String sessionId) {
        return threads.findMain(sessionId).orElseThrow(() -> notFound(sessionId));
    }

    /**
     * 读取会话中的一条线程。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @return 线程
     * @throws NotFoundException 会话不存在（{@code session.not_found}）或会话中没有该线程（{@code thread.not_found}）时
     */
    public SessionThread thread(String sessionId, String threadId) {
        get(sessionId);
        return threads.find(threadId)
                .filter(thread -> thread.sessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException(
                        SessionErrorCode.THREAD_NOT_FOUND, "会话 " + sessionId + " 中没有线程 " + threadId));
    }

    /**
     * 要求会话可写：未终止。
     *
     * @param session 会话
     * @throws ConflictException 会话已终止时（{@code session.terminated}）
     */
    public static void requireWritable(Session session) {
        if (session.isTerminated()) {
            throw terminated(session.id());
        }
    }

    private void terminate(String sessionId, SessionEvent.TerminationReason reason) {
        cancelActive(sessionId);
        pendingActions.expirePending(sessionId, clock.instant());
        events.append(EventBatch.unfenced(sessionId, null, List.of(new SessionEvent.StatusTerminated(reason))));
    }

    private void lockMainThread(String sessionId) {
        // 与发送消息、结束轮次的事务串行（ADR-0028 第 8 条）：终止之后不会再有消息进入收件箱或生成新的轮次
        threads.findMain(sessionId).ifPresent(thread -> threadLocks.lock(thread.id()));
    }

    private static void validateMetadata(Map<String, String> metadata) {
        List<FieldViolation> violations = new ArrayList<>();
        for (String key : metadata.keySet()) {
            if (key.isEmpty() || key.length() > METADATA_KEY_MAX) {
                violations.add(new FieldViolation("metadata", "键的长度应在 1 至 " + METADATA_KEY_MAX + " 之间：" + key));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "元数据的键不合法", violations);
        }
    }

    private static void validateCustomTools(List<CustomToolDeclaration> tools) {
        Set<String> names = new HashSet<>();
        for (CustomToolDeclaration tool : tools) {
            if (!names.add(tool.name())) {
                throw new ValidationException(
                        CommonErrorCode.VALIDATION_FAILED,
                        "自定义工具重名：" + tool.name(),
                        List.of(new FieldViolation("custom_tools", "工具名重复：" + tool.name())));
            }
        }
    }

    /**
     * 取消会话的全部未结束轮次；执行中的轮次在提交后通知持有租约的节点，它立即续租、发现租约已失去并放弃本轮（架构设计「消息投递」
     * 中"删除会话：取消当前轮"），通知丢失时由续租兜底。
     */
    private void cancelActive(String sessionId) {
        String mainThreadId = mainThread(sessionId).id();
        for (ActiveTurn turn : transitions.cancelActive(sessionId)) {
            if (turn.running()) {
                nodeChannel.notifyAfterCommit(turn.leaseOwner(), mainThreadId);
            }
        }
    }

    /**
     * 自定义工具不能与保留工具名重名，也不能以 {@code mcp__} 开头（架构设计「待处理动作」中的"自定义工具"）。
     *
     * @throws ValidationException 有冲突时，列出每个冲突的名字
     */
    private static void rejectReservedNames(List<CustomToolDeclaration> tools, Set<String> reserved) {
        List<FieldViolation> violations = tools.stream()
                .map(CustomToolDeclaration::name)
                .filter(name -> reserved.contains(name) || PlatformToolNames.isMcpTool(name))
                .map(name -> new FieldViolation("custom_tools", "工具名与平台保留的工具名冲突：" + name))
                .toList();
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "自定义工具名与平台保留的工具名冲突", violations);
        }
    }

    private static NotFoundException notFound(String sessionId) {
        return new NotFoundException(SessionErrorCode.SESSION_NOT_FOUND, "会话不存在：" + sessionId);
    }

    private static ConflictException terminated(String sessionId) {
        return new ConflictException(SessionErrorCode.SESSION_TERMINATED, "会话已归档或删除，只读：" + sessionId);
    }
}
