package cn.refinex.meridian.runtime.delivery.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.delivery.domain.TakenMessage;
import cn.refinex.meridian.runtime.delivery.infra.InboxRepository;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * 收件箱：{@link InboxPort} 的数据库实现，以及 Worker 在轮次结束与恢复时使用的合并、重新投递（架构设计「端口」「消息投递」
 * 「崩溃恢复」）。
 *
 * <ul>
 *   <li>{@link #consumeSteer}：{@code SteerMiddleware}（T08）在每次推理前调用，取出 steer 消息并记录 {@code consumed_turn_id}、
 *       {@code consumed_epoch}，写入带租约条件。
 *   <li>{@link #takePending}：轮次以空闲结束时，把待投递的消息合并成下一轮的输入，在 Worker 的结束事务中调用。
 *   <li>{@link #redeliverSteer}：恢复认领时，失败那次执行的内容没有保存，被它消费的 steer 恢复为待投递。
 * </ul>
 * 消息内容取自对应的 {@code user.message} 事件。项目级仓储从 {@code PlatformContext} 取租户与项目：阻塞方法要求调用线程上已绑定
 * 上下文（{@code Blocking} 从 Reactor Context 绑定）。事务传播为 REQUIRED，可以并入调用方的事务。
 *
 * <p>线程安全：无状态。
 */
@Service
public class DatabaseInbox implements InboxPort {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInbox.class);

    private final InboxRepository repository;
    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建收件箱。
     *
     * @param repository 收件箱仓储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param nodeId 本节点 ID，租约条件中的 {@code lease_owner}
     * @param clock 时钟
     */
    public DatabaseInbox(InboxRepository repository, TransactionTemplate transactions, NodeId nodeId, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    @Override
    public Mono<List<InboundMessage>> consumeSteer(ThreadRef thread, String turnId, long epoch) {
        return Blocking.call(() -> transactions.execute(status -> {
            if (!repository.lockLease(turnId, nodeId.value(), epoch)) {
                throw new LeaseLostException("轮次 " + turnId + " 在本节点的租约不是 epoch " + epoch + "，拒绝消费 steer 消息");
            }
            return toMessages(repository.consumeSteer(thread.threadId(), turnId, epoch, clock.instant()));
        }));
    }

    /**
     * 写入一条待投递的消息（API 在写 {@code user.message} 的同一事务中调用，T07）。
     *
     * @param entry 消息
     * @return 收件箱记录 ID
     */
    public String add(InboxEntry entry) {
        return repository.insert(entry, clock.instant());
    }

    /**
     * 取出线程中全部待投递的消息，作为下一轮的输入；没有来得及注入的 steer 降级为 queue（架构设计「消息投递」）。必须在
     * 调用方的事务中调用，与插入下一轮一起提交。
     *
     * @param threadId 线程 ID
     * @param nextTurnId 下一轮的 ID
     * @return 按到达顺序的消息；没有时为空列表
     */
    public List<InboundMessage> takePending(String threadId, String nextTurnId) {
        return toMessages(repository.takePending(threadId, nextTurnId, clock.instant()));
    }

    /**
     * 重新投递被失败那次执行消费的 steer 消息（架构设计「崩溃恢复」）。
     *
     * @param turnId 轮次 ID
     * @param failedEpoch 失败那次执行的 epoch
     * @return 重新投递的条数
     */
    public int redeliverSteer(String turnId, long failedEpoch) {
        int count = repository.redeliverSteer(turnId, failedEpoch);
        if (count > 0) {
            log.info("恢复时重新投递 steer 消息 turnId={} failedEpoch={} count={}", turnId, failedEpoch, count);
        }
        return count;
    }

    private static List<InboundMessage> toMessages(List<TakenMessage> taken) {
        return taken.stream()
                .sorted(TakenMessage.ARRIVAL_ORDER)
                .map(DatabaseInbox::toMessage)
                .toList();
    }

    private static InboundMessage toMessage(TakenMessage message) {
        if (message.eventData() == null) {
            throw new IllegalStateException("收件箱消息对应的 user.message 事件不存在：" + message.eventId());
        }
        UserEvent.Message event = (UserEvent.Message) EngineJson.readEvent(EventType.USER_MESSAGE, message.eventData());
        return new InboundMessage(message.eventId(), event.content());
    }
}
