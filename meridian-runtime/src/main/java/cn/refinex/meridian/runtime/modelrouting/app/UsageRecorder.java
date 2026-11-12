package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelUsageMessage;
import cn.refinex.meridian.runtime.modelrouting.infra.system.TurnUsageRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写用量记录：outbox 的 {@code usage} 主题（架构设计「跨服务数据流」：outbox → Redis Stream {@code usage} → control-plane 消费者）。
 * 会话中的模型调用（{@code model_tokens}，带会话与轮次）在同一事务累加进 {@code rt.turn_usage}，{@code session.usage} 因此与账本逐条
 * 一致（ADR-0050）。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Service
public class UsageRecorder {

    /** 用量的 outbox 主题（架构设计「跨服务数据流」）。 */
    public static final String TOPIC = "usage";

    private final OutboxWriter outbox;
    private final TurnUsageRepository turnUsage;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用量记录器。
     *
     * @param outbox outbox 写入
     * @param turnUsage 每轮的用量累计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public UsageRecorder(
            OutboxWriter outbox, TurnUsageRepository turnUsage, TransactionTemplate transactions, Clock clock) {
        this.outbox = outbox;
        this.turnUsage = turnUsage;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 在独立的事务中写一条用量记录。
     *
     * @param message 用量记录
     * @return outbox 消息 ID
     */
    public String record(ModelUsageMessage message) {
        return transactions.execute(status -> {
            String id = outbox.append(TOPIC, message);
            if (ModelUsageMessage.KIND_MODEL_TOKENS.equals(message.kind())
                    && message.sessionId() != null
                    && message.turnId() != null) {
                turnUsage.add(message, clock.instant());
            }
            return id;
        });
    }
}
