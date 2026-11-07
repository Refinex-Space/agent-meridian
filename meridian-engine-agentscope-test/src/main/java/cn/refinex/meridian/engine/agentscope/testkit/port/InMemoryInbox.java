package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * {@link InboxPort} 的内存实现：测试用 {@link #steer} 投递 steer 消息，内核取出时记录消费的轮次与 epoch。
 *
 * <p>线程安全：同一线程的取出在本对象的监视器内完成，消息只会被消费一次。
 *
 * <p>见架构设计「端口」「消息投递」。
 */
public final class InMemoryInbox implements InboxPort {

    private final Map<String, List<InboundMessage>> pending = new ConcurrentHashMap<>();
    private final List<Consumption> consumptions = new CopyOnWriteArrayList<>();

    /**
     * 向线程投递一条 steer 消息。
     *
     * @param threadId 线程 ID
     * @param message 消息
     */
    public synchronized void steer(String threadId, InboundMessage message) {
        pending.computeIfAbsent(threadId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public Mono<List<InboundMessage>> consumeSteer(ThreadRef thread, String turnId, long epoch) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                List<InboundMessage> messages = pending.remove(thread.threadId());
                if (messages == null) {
                    return List.of();
                }
                messages.forEach(m -> consumptions.add(new Consumption(m.eventId(), turnId, epoch)));
                return List.copyOf(messages);
            }
        });
    }

    /**
     * 已消费的记录，按消费顺序。
     *
     * @return 记录
     */
    public List<Consumption> consumptions() {
        return List.copyOf(consumptions);
    }

    /**
     * 一条消息的消费记录，对应 {@code consumed_turn_id} 与 {@code consumed_epoch}。
     *
     * @param eventId 消息的事件 ID
     * @param turnId 消费它的轮次
     * @param epoch 消费它的那次执行的租约 epoch
     */
    public record Consumption(String eventId, String turnId, long epoch) {}
}
