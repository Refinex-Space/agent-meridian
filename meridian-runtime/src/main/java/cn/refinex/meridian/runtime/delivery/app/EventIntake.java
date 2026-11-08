package cn.refinex.meridian.runtime.delivery.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.approval.app.PendingActionResolver;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.delivery.domain.EventAdmission;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.app.NodeChannel;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.app.TurnTransitions;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.ActiveTurn;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.SessionErrorCode;
import cn.refinex.meridian.runtime.session.domain.SessionThread;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.WriteFence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 接收调用方发送的用户事件（{@code POST /v1/sessions/{id}/events}）：在一个事务里校验事件与会话状态的组合、按线程状态投递消息与中断、
 * 记录待处理动作的回应并在全部回应后生成恢复轮、持久化事件（架构设计「一轮执行的生命周期」「消息投递」「待处理动作」）。
 * 任一事件不合法时整批回滚。
 *
 * <p>事务内的顺序：
 * <ol>
 *   <li>取主线程的事务级咨询锁，与 Worker 结束轮次时合并收件箱的事务串行，否则两边都看不到对方未提交的数据，消息会留在收件箱里
 *       没有轮次处理（ADR-0028 第 8 条）。锁住之后再读会话状态，归档与删除也取同一把锁。
 *   <li>校验：会话未终止；回应事件对应仍在等待、类型匹配的动作（{@link EventAdmission}，动作行被锁到事务结束）。锁住主线程的
 *       未结束轮次，此后认领、回收与带租约的写入都要等本事务提交。
 *   <li>{@code user.interrupt}，按本批到达时的线程状态：空闲时无效果；执行中写 {@code interrupt_requested_at}，提交后通知
 *       {@code node:{lease_owner}}；排队中的消息轮直接置为 {@code cancelled}（排队中的恢复轮带着中断请求执行，把结论写进上下文后
 *       停止，否则状态里待确认的调用没有结论）；等待回应时对全部待处理动作生成拒绝结论。
 *   <li>回应事件：记录结论与"记住此决定"；一轮的全部动作都有结论后生成恢复轮（{@link PendingActionResolver}）。
 *   <li>{@code user.message}：先生成事件 ID（登记事项 D10）。线程空闲时把收件箱中尚未投递的消息与本批的全部消息合成一个
 *       {@code messages} 轮；否则逐条写入收件箱，等待回应时 steer 降级为 queue（恢复轮结束后才成为下一轮）。按锁顺序"同一事务里
 *       既入队又写事件时先入队"（ADR-0027）。
 *   <li>按请求顺序写入事件：消息与中断属于主线程，回应事件属于产生动作的线程；最后写被取消的排队轮次的
 *       {@code session.status_idle(interrupted)}。
 * </ol>
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Service
public class EventIntake {

    private static final Logger log = LoggerFactory.getLogger(EventIntake.class);

    private final SessionRepository sessions;
    private final ThreadRepository threads;
    private final ThreadLockRepository threadLocks;
    private final PendingActionRepository pendingActions;
    private final TurnEnqueuer enqueuer;
    private final TurnTransitions transitions;
    private final DatabaseInbox inbox;
    private final PendingActionResolver resolver;
    private final NodeChannel nodeChannel;
    private final SessionEventWriter events;
    private final TransactionTemplate transactions;

    /**
     * 创建用例。
     *
     * @param sessions 会话仓储
     * @param threads 线程仓储
     * @param threadLocks 线程咨询锁
     * @param pendingActions 待处理动作仓储
     * @param enqueuer 轮次入队
     * @param transitions 轮次迁移：锁定线程的轮次、中断、取消
     * @param inbox 收件箱
     * @param resolver 待处理动作的结论与恢复轮
     * @param nodeChannel 节点频道，中断时通知持有租约的节点
     * @param events 事件写入入口
     * @param transactions 事务模板（传播行为 REQUIRED）
     */
    public EventIntake(
            SessionRepository sessions,
            ThreadRepository threads,
            ThreadLockRepository threadLocks,
            PendingActionRepository pendingActions,
            TurnEnqueuer enqueuer,
            TurnTransitions transitions,
            DatabaseInbox inbox,
            PendingActionResolver resolver,
            NodeChannel nodeChannel,
            SessionEventWriter events,
            TransactionTemplate transactions) {
        this.sessions = sessions;
        this.threads = threads;
        this.threadLocks = threadLocks;
        this.pendingActions = pendingActions;
        this.enqueuer = enqueuer;
        this.transitions = transitions;
        this.inbox = inbox;
        this.resolver = resolver;
        this.nodeChannel = nodeChannel;
        this.events = events;
        this.transactions = transactions;
    }

    /**
     * 接收一批用户事件。
     *
     * @param sessionId 会话 ID
     * @param received 用户事件，按请求顺序，至少一个
     * @return 持久化后的事件，顺序与请求相同
     * @throws NotFoundException 会话不存在或不属于当前项目时（{@code session.not_found}）
     * @throws cn.refinex.meridian.common.error.ConflictException 会话已终止（{@code session.terminated}），或回应事件与
     *     待处理动作的组合不合法（{@code event.*}）时
     */
    public List<StoredEvent> send(String sessionId, List<UserEvent> received) {
        List<UserEvent> batch = List.copyOf(received);
        var actionIds = EventAdmission.respondedActionIds(batch);
        List<UserEvent.Delivery> deliveries = EventAdmission.deliveries(batch);
        List<StoredEvent> stored = transactions.execute(status -> {
            SessionThread main = threads.findMain(sessionId).orElseThrow(() -> notFound(sessionId));
            threadLocks.lock(main.id());
            Session session = sessions.find(sessionId).orElseThrow(() -> notFound(sessionId));
            SessionService.requireWritable(session);
            Map<String, StoredPendingAction> actions = pendingActions.lockAll(sessionId, actionIds).stream()
                    .collect(Collectors.toMap(StoredPendingAction::id, Function.identity()));
            EventAdmission.checkResponses(batch, actions);
            Optional<ActiveTurn> active = transitions.lockActiveOnThread(main.id());

            List<String> eventIds =
                    batch.stream().map(e -> Ids.newId(IdPrefix.EVENT)).toList();
            String cancelledTurnId = batch.stream().anyMatch(UserEvent.Interrupt.class::isInstance)
                    ? interrupt(session, main, active.orElse(null))
                    : null;
            respond(session, batch, actions);
            deliverMessages(session, main, batch, deliveries, eventIds);
            List<StoredEvent> appended = append(sessionId, main.id(), batch, actions, eventIds);
            if (cancelledTurnId != null) {
                events.append(new EventBatch(
                        sessionId,
                        main.id(),
                        cancelledTurnId,
                        WriteFence.none(),
                        List.of(new SessionEvent.StatusIdle(StopReason.INTERRUPTED))));
            }
            return appended;
        });
        log.debug("用户事件已接收 sessionId={} count={}", sessionId, stored.size());
        return stored;
    }

    /**
     * 按本批到达时主线程的状态处理中断（架构设计「消息投递」中 {@code user.interrupt} 一行）。
     *
     * @return 被取消的排队轮次；没有取消时为空
     */
    private String interrupt(Session session, SessionThread main, ActiveTurn active) {
        if (active == null) {
            log.debug("线程空闲，中断无效果 sessionId={}", session.id());
            return null;
        }
        if (active.running()) {
            if (transitions.requestInterrupt(active.turnId())) {
                nodeChannel.notifyAfterCommit(active.leaseOwner(), main.id());
                log.info("已请求中断执行中的轮次 turnId={} leaseOwner={}", active.turnId(), active.leaseOwner());
            }
            return null;
        }
        if (active.awaitingAction()) {
            resolver.interrupt(active.turnId());
            resolver.resumeIfComplete(session.id(), main.id(), active.turnId());
            return null;
        }
        if (active.messages()) {
            transitions.cancelQueued(active.turnId());
            log.info("排队中的轮次被中断，已取消 turnId={}", active.turnId());
            return active.turnId();
        }
        // 排队中的恢复轮：取消会让状态里待确认的调用失去结论，改为带着中断请求执行，写入结论后停止
        transitions.requestInterrupt(active.turnId());
        log.info("排队中的恢复轮被中断，执行时写入结论后停止 turnId={} kind={}", active.turnId(), active.kind());
        return null;
    }

    /** 记录回应事件的结论，一轮的全部动作都有结论时生成恢复轮。 */
    private void respond(Session session, List<UserEvent> batch, Map<String, StoredPendingAction> actions) {
        String decidedBy = PlatformContexts.current().apiKeyId();
        Map<String, StoredPendingAction> turns = new LinkedHashMap<>();
        for (UserEvent event : batch) {
            String actionId = EventAdmission.actionIdOf(event);
            if (actionId != null) {
                StoredPendingAction action = actions.get(actionId);
                resolver.respond(action, event, decidedBy);
                turns.putIfAbsent(action.turnId(), action);
            }
        }
        for (StoredPendingAction action : turns.values()) {
            resolver.resumeIfComplete(session.id(), action.threadId(), action.turnId());
        }
    }

    private void deliverMessages(
            Session session,
            SessionThread main,
            List<UserEvent> batch,
            List<UserEvent.Delivery> deliveries,
            List<String> eventIds) {
        List<Integer> messages = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            if (batch.get(i) instanceof UserEvent.Message) {
                messages.add(i);
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        // 中断与回应可能已经改变了线程的轮次，按处理之后的状态投递
        Optional<ActiveTurn> active = transitions.lockActiveOnThread(main.id());
        if (active.isEmpty() && enqueueWithPending(session, main, batch, messages, eventIds)) {
            return;
        }
        // 等待回应时 steer 降级为 queue：恢复轮结束后才成为下一轮（架构设计「消息投递」）
        boolean awaiting = active.map(ActiveTurn::awaitingAction).orElse(false);
        for (int i : messages) {
            UserEvent.Delivery delivery = awaiting ? UserEvent.Delivery.QUEUE : deliveries.get(i);
            inbox.add(new InboxEntry(session.id(), main.id(), eventIds.get(i), delivery));
        }
    }

    /**
     * 线程空闲：收件箱中尚未投递的消息（排队的轮次被中断取消时留下的）在前，本批的消息在后，合成一个 {@code messages} 轮。
     *
     * @return 入队成功时为 true；线程忙时为 false，由调用方写入收件箱
     */
    private boolean enqueueWithPending(
            Session session, SessionThread main, List<UserEvent> batch, List<Integer> messages, List<String> eventIds) {
        String turnId = Ids.newId(IdPrefix.TURN);
        List<InboundMessage> inbound = new ArrayList<>(inbox.takePending(main.id(), turnId));
        for (int i : messages) {
            inbound.add(new InboundMessage(eventIds.get(i), ((UserEvent.Message) batch.get(i)).content()));
        }
        EnqueueResult result = enqueuer.enqueue(
                turnId,
                NewTurn.of(
                        session.tenantId(),
                        session.projectId(),
                        session.id(),
                        main.id(),
                        new TurnInput.NewMessages(inbound, null)));
        if (result instanceof EnqueueResult.ThreadBusy) {
            // 线程咨询锁与轮次行锁下不会发生；发生时取出的消息随事务回滚
            throw new IllegalStateException("线程空闲时入队失败：" + main.id());
        }
        return true;
    }

    private List<StoredEvent> append(
            String sessionId,
            String mainThreadId,
            List<UserEvent> batch,
            Map<String, StoredPendingAction> actions,
            List<String> eventIds) {
        List<StoredEvent> stored = new ArrayList<>(batch.size());
        int start = 0;
        while (start < batch.size()) {
            String threadId = threadOf(batch.get(start), actions, mainThreadId);
            int end = start + 1;
            while (end < batch.size()
                    && threadOf(batch.get(end), actions, mainThreadId).equals(threadId)) {
                end++;
            }
            // 相邻且同一线程的事件合成一批，批与批在同一事务里按请求顺序写入，seq 仍然连续
            List<EngineEvent> slice = List.copyOf(batch.subList(start, end));
            AppendResult result =
                    events.append(EventBatch.unfenced(sessionId, threadId, slice), eventIds.subList(start, end));
            stored.addAll(((AppendResult.Appended) result).events());
            start = end;
        }
        return stored;
    }

    private static String threadOf(UserEvent event, Map<String, StoredPendingAction> actions, String mainThreadId) {
        String actionId = EventAdmission.actionIdOf(event);
        return actionId == null ? mainThreadId : actions.get(actionId).threadId();
    }

    private static NotFoundException notFound(String sessionId) {
        return new NotFoundException(SessionErrorCode.SESSION_NOT_FOUND, "会话不存在：" + sessionId);
    }
}
