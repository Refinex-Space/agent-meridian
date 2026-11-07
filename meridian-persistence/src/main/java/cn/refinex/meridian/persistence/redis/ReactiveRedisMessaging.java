package cn.refinex.meridian.persistence.redis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Redis Stream 与 Pub/Sub 的响应式客户端薄封装，供 runtime（WebFlux）使用，语义与 {@link RedisMessaging} 相同。
 * 所有方法都返回惰性的 {@code Mono}/{@code Flux}，不阻塞 Reactor 线程。
 *
 * <p>线程安全：可共享。
 */
public final class ReactiveRedisMessaging {

    private final ReactiveStringRedisTemplate template;

    /**
     * 创建客户端。
     *
     * @param template 字符串序列化的响应式 Redis 模板
     */
    public ReactiveRedisMessaging(ReactiveStringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 向 Stream 追加一条消息（XADD）。
     *
     * @param stream Stream 的键
     * @param fields 消息字段
     * @return 条目 ID
     */
    public Mono<String> publish(String stream, Map<String, String> fields) {
        return template.opsForStream().add(stream, fields).map(RecordId::getValue);
    }

    /**
     * 确保消费组存在，语义同 {@link RedisMessaging#ensureGroup}。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @return 完成信号
     */
    public Mono<Void> ensureGroup(String stream, String group) {
        ByteBuffer key = ByteBuffer.wrap(stream.getBytes(StandardCharsets.UTF_8));
        return template.execute(
                        connection -> connection.streamCommands().xGroupCreate(key, group, ReadOffset.from("0"), true))
                .onErrorResume(RedisMessaging::isBusyGroup, e -> Mono.empty())
                .then();
    }

    /**
     * 以消费组读取新消息（XREADGROUP {@code >}），语义同 {@link RedisMessaging#readGroup}。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param consumer 消费者名
     * @param count 最多读取的条数
     * @param block 没有新消息时最多等待的时长；为零表示不等待
     * @return 读到的消息
     */
    public Flux<StreamMessage> readGroup(String stream, String group, String consumer, int count, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        if (!block.isZero()) {
            options = options.block(block);
        }
        return template.opsForStream()
                .read(Consumer.from(group, consumer), options, StreamOffset.create(stream, ReadOffset.lastConsumed()))
                .map(RedisMessaging::toMessage);
    }

    /**
     * 确认消息已处理（XACK）。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param recordIds 条目 ID
     * @return 实际确认的条数
     */
    public Mono<Long> ack(String stream, String group, Collection<String> recordIds) {
        if (recordIds.isEmpty()) {
            return Mono.just(0L);
        }
        return template.opsForStream().acknowledge(stream, group, recordIds.toArray(String[]::new));
    }

    /**
     * 认领空闲超过 {@code minIdle} 的待确认消息，语义同 {@link RedisMessaging#claimIdle}。
     * 先取最早的 {@code count} 条待确认消息，再由 XCLAIM 按最小空闲时长筛选。
     *
     * @param stream Stream 的键
     * @param group 消费组名
     * @param consumer 认领者
     * @param minIdle 最小空闲时长
     * @param count 最多认领的条数
     * @return 认领到的消息
     */
    public Flux<StreamMessage> claimIdle(String stream, String group, String consumer, Duration minIdle, int count) {
        return template.opsForStream()
                .pending(stream, group, Range.unbounded(), count)
                .flatMapMany(pending -> pending.isEmpty()
                        ? Flux.empty()
                        : template.opsForStream()
                                .claim(
                                        stream,
                                        group,
                                        consumer,
                                        minIdle,
                                        pending.stream().map(p -> p.getId()).toArray(RecordId[]::new)))
                .map(RedisMessaging::toMessage);
    }

    /**
     * 向 Pub/Sub 频道发布一条通知。
     *
     * @param channel 频道名
     * @param message 通知内容
     * @return 收到通知的订阅者数量
     */
    public Mono<Long> notify(String channel, String message) {
        return template.convertAndSend(channel, message);
    }

    /**
     * 订阅 Pub/Sub 频道，取消订阅 {@code Flux} 即退订。
     *
     * @param channel 频道名
     * @return 通知内容流
     */
    public Flux<String> subscribe(String channel) {
        return template.listenToChannel(channel).map(ReactiveSubscription.Message::getMessage);
    }
}
