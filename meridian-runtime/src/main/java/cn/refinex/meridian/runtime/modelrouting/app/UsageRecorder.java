package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelUsageMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写用量记录：outbox 的 {@code usage} 主题（架构设计「跨服务数据流」：outbox → Redis Stream {@code usage} → control-plane 消费者）。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Service
public class UsageRecorder {

    /** 用量的 outbox 主题（架构设计「跨服务数据流」）。 */
    public static final String TOPIC = "usage";

    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    /**
     * 创建用量记录器。
     *
     * @param outbox outbox 写入
     * @param transactions 事务模板
     */
    public UsageRecorder(OutboxWriter outbox, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * 在独立的事务中写一条用量记录。
     *
     * @param message 用量记录
     * @return outbox 消息 ID
     */
    public String record(ModelUsageMessage message) {
        return transactions.execute(status -> outbox.append(TOPIC, message));
    }
}
