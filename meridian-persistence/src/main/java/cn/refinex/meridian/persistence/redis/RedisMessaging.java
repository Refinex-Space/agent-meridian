package cn.refinex.meridian.persistence.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Stream 与 Pub/Sub 的阻塞客户端薄封装，供 MVC 服务（control-plane、knowledge）与后台作业使用；
 * runtime 用 {@link ReactiveRedisMessaging}。Redis 在平台中只做实时流、限流与通知（架构设计「架构原则」第 7 条）。
 *
 * <p>Stream 消费组的用法：
 * <ol>
 *   <li>{@link #ensureGroup} 创建消费组（Stream 不存在时一并创建），从最早的条目开始，组建立前写入的消息也不会丢；
 *   <li>{@link #readGroup} 读取新消息，处理成功后 {@link #ack} 确认；
 *   <li>消费者崩溃后未确认的消息留在待确认列表中，由存活的消费者用 {@link #claimIdle} 认领后重新处理（重投）。
 * </ol>
 * 投递语义为至少一次，处理逻辑必须幂等。
 *
 * <p>线程安全：可共享。
 */
public final class RedisMessaging {

    private final StringRedisTemplate template;
    private final RedisMessageListenerContainer listenerContainer;

    /**
     * 创建客户端。
     *
     * @param template 字符串序列化的 Redis 模板
     * @param listenerContainer Pub/Sub 订阅容器，需已启动（作为 Spring Bean 时自动启动）
     */
    public RedisMessaging(StringRedisTemplate template, RedisMessageListenerContainer listenerContainer) {
        this.template = template;
        this.listenerContainer = listenerContainer;
    }

    /**
     * 向 Stream 追加一条消息（XADD，条目 ID 由 Redis 生成）。
     *
     * @param stream Stream 的键
     * @param fields 消息字段
     * @return 条目 ID
     */
    public String publish(String stream, Map<String, String> fields) {
        RecordId id = template.opsForStream().add(stream, fields);
        return id.getValue();
    }

    /**
     * 确保消费组存在：不存在时从最早的条目开始创建，Stream 不存在时一并创建；已存在时什么也不做。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     */
    public void ensureGroup(String stream, String group) {
        try {
            template.execute((RedisCallback<String>) connection -> connection
                    .streamCommands()
                    .xGroupCreate(stream.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0"), true));
        } catch (RedisSystemException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    /**
     * 以消费组读取尚未投递给任何消费者的新消息（XREADGROUP {@code >}）。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param consumer 消费者名，同一组内唯一，通常取实例 ID
     * @param count 最多读取的条数
     * @param block 没有新消息时最多等待的时长；为零表示不等待
     * @return 读到的消息，没有时为空列表
     */
    public List<StreamMessage> readGroup(String stream, String group, String consumer, int count, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        if (!block.isZero()) {
            options = options.block(block);
        }
        List<MapRecord<String, Object, Object>> records = template.opsForStream()
                .read(
                        org.springframework.data.redis.connection.stream.Consumer.from(group, consumer),
                        options,
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records == null
                ? List.of()
                : records.stream().map(RedisMessaging::toMessage).toList();
    }

    /**
     * 确认消息已处理（XACK），从待确认列表中移除。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param recordIds 条目 ID
     * @return 实际确认的条数
     */
    public long ack(String stream, String group, Collection<String> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        Long acked = template.opsForStream().acknowledge(stream, group, recordIds.toArray(String[]::new));
        return acked == null ? 0 : acked;
    }

    /**
     * 认领空闲超过 {@code minIdle} 的待确认消息（XPENDING + XCLAIM），用于消费者崩溃后的重投。
     * 认领后这些消息归 {@code consumer} 所有，处理成功后照常 {@link #ack}。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param consumer 认领者
     * @param minIdle 最小空闲时长：投递后超过这么久仍未确认，才视为原消费者已失效
     * @param count 最多认领的条数
     * @return 认领到的消息
     */
    public List<StreamMessage> claimIdle(String stream, String group, String consumer, Duration minIdle, int count) {
        PendingMessages pending = template.opsForStream().pending(stream, group, Range.unbounded(), count, minIdle);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = pending.stream().map(p -> p.getId()).toArray(RecordId[]::new);
        List<MapRecord<String, Object, Object>> claimed =
                template.opsForStream().claim(stream, group, consumer, minIdle, ids);
        return claimed == null
                ? List.of()
                : claimed.stream().map(RedisMessaging::toMessage).toList();
    }

    /**
     * 向 Pub/Sub 频道发布一条通知。通知可能丢失，接收方要有数据库兜底（架构设计「存储分工」）。
     *
     * @param channel 频道名，例如 {@code config.invalidate}
     * @param message 通知内容
     * @return 收到通知的订阅者数量
     */
    public long notify(String channel, String message) {
        Long receivers = template.convertAndSend(channel, message);
        return receivers == null ? 0 : receivers;
    }

    /**
     * 订阅 Pub/Sub 频道。回调在订阅容器的线程上执行，不能长时间阻塞。
     *
     * @param channel 频道名
     * @param listener 收到通知时的回调
     * @return 订阅句柄，关闭即取消订阅
     */
    public Subscription subscribe(String channel, Consumer<String> listener) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener adapter =
                (message, pattern) -> listener.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        listenerContainer.addMessageListener(adapter, topic);
        return () -> listenerContainer.removeMessageListener(adapter, topic);
    }

    static StreamMessage toMessage(MapRecord<String, ?, ?> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        return new StreamMessage(record.getStream(), record.getId().getValue(), fields);
    }

    static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /** Pub/Sub 订阅句柄。 */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {

        /** 取消订阅。 */
        @Override
        void close();
    }
}
