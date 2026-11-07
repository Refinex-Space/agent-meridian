package cn.refinex.meridian.runtime.delivery.domain;

import java.time.Instant;
import java.util.Comparator;

/**
 * 从收件箱取出（标记为已消费）的一条消息，带上对应 {@code user.message} 事件的内容。
 *
 * @param inboxId 收件箱记录 ID
 * @param eventId {@code user.message} 事件 ID
 * @param eventData 事件内容 JSON；事件不存在（已归档）时为空
 * @param createdAt 写入收件箱的时间
 */
public record TakenMessage(String inboxId, String eventId, String eventData, Instant createdAt) {

    /** 到达顺序：先按写入时间，再按记录 ID（ULID，同一毫秒内也有序）。 */
    public static final Comparator<TakenMessage> ARRIVAL_ORDER =
            Comparator.comparing(TakenMessage::createdAt).thenComparing(TakenMessage::inboxId);
}
