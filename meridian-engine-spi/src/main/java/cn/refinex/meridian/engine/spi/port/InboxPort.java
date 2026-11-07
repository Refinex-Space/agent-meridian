package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 读取并标记线程收件箱中的 steer 消息，供 {@code SteerMiddleware} 在每次推理前调用，由 runtime 的 {@code delivery}
 * 实现，表为 {@code rt.inbox_messages}，见架构设计「端口」「消息投递」「崩溃恢复」。
 */
public interface InboxPort {

    /**
     * 原子地取出线程中 {@code delivery=steer} 的待投递消息，并标记为已消费：记录 {@code consumed_turn_id}
     * 与 {@code consumed_epoch}，写入带租约条件。崩溃恢复时，{@code consumed_epoch} 等于失败 epoch 的消息会被重新投递。
     *
     * @param thread 线程
     * @param turnId 消费它们的轮次 ID
     * @param epoch 消费它们的那次执行的租约 epoch
     * @return 按到达顺序排列的消息；没有时为空列表
     */
    Mono<List<InboundMessage>> consumeSteer(ThreadRef thread, String turnId, long epoch);
}
