package cn.refinex.meridian.runtime.delivery.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.delivery.domain.EventAdmission;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 接收调用方发送的用户事件（{@code POST /v1/sessions/{id}/events}）：在一个事务里校验事件与会话状态的组合、写收件箱或入队、
 * 持久化事件（架构设计「一轮执行的生命周期」「消息投递」）。任一事件不合法时整批回滚。
 *
 * <p>事务内的顺序：
 * <ol>
 *   <li>取主线程的事务级咨询锁，与 Worker 结束轮次时合并收件箱的事务串行，否则两边都看不到对方未提交的数据，消息会留在收件箱里
 *       没有轮次处理（ADR-0028 第 8 条，登记事项 D12）。锁住之后再读会话状态，归档与删除也取同一把锁。
 *   <li>校验：会话未终止；回应事件对应仍在等待、类型匹配的动作（{@link EventAdmission}，动作行被锁到事务结束）。
 *   <li>{@code user.message}：先生成事件 ID（登记事项 D10），主线程没有未结束的轮次时把本批的全部消息入队为一个 {@code messages} 轮，
 *       否则逐条写入收件箱，按投递方式投递。按锁顺序"同一事务里既入队又写事件时先入队"（ADR-0027）。
 *   <li>按请求顺序写入事件：消息与中断属于主线程，回应事件属于产生动作的线程。
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
    private final DatabaseInbox inbox;
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
     * @param inbox 收件箱
     * @param events 事件写入入口
     * @param transactions 事务模板（传播行为 REQUIRED）
     */
    public EventIntake(
            SessionRepository sessions,
            ThreadRepository threads,
            ThreadLockRepository threadLocks,
            PendingActionRepository pendingActions,
            TurnEnqueuer enqueuer,
            DatabaseInbox inbox,
            SessionEventWriter events,
            TransactionTemplate transactions) {
        this.sessions = sessions;
        this.threads = threads;
        this.threadLocks = threadLocks;
        this.pendingActions = pendingActions;
        this.enqueuer = enqueuer;
        this.inbox = inbox;
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

            List<String> eventIds =
                    batch.stream().map(e -> Ids.newId(IdPrefix.EVENT)).toList();
            deliverMessages(session, main, batch, deliveries, eventIds);
            // TODO(T08): user.interrupt 写 turns.interrupt_requested_at 并通知持有租约的节点；回应事件把动作标为已回应，
            //  全部回应后生成恢复轮（架构设计「消息投递」「待处理动作」）
            return append(sessionId, main.id(), batch, actions, eventIds);
        });
        log.debug("用户事件已接收 sessionId={} count={}", sessionId, stored.size());
        return stored;
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
        List<InboundMessage> inbound = messages.stream()
                .map(i -> new InboundMessage(eventIds.get(i), ((UserEvent.Message) batch.get(i)).content()))
                .toList();
        EnqueueResult result = enqueuer.enqueue(NewTurn.of(
                session.tenantId(),
                session.projectId(),
                session.id(),
                main.id(),
                new TurnInput.NewMessages(inbound, null)));
        if (result instanceof EnqueueResult.ThreadBusy) {
            // 主线程有未结束的轮次：消息进收件箱，queue 在本轮结束后合并成下一轮，steer 由 SteerMiddleware 注入当前轮
            for (int i : messages) {
                inbox.add(new InboxEntry(session.id(), main.id(), eventIds.get(i), deliveries.get(i)));
            }
        }
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
