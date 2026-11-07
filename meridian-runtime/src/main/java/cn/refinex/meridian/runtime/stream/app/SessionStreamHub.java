package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.persistence.redis.StreamMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * 本节点读取会话事件 Stream {@code ev:{session_id}} 的唯一入口：所有 SSE 连接共用一个轮询循环，每次用一条不阻塞的 XREAD
 * 读取全部被订阅的会话（架构设计「SSE 协议」第 1 步"先订阅 Redis Stream 并缓冲"）。
 *
 * <p>为什么不让每个连接各自读：阻塞的 XREAD 会独占连接，Spring Data Redis 为每次阻塞读取另开一个连接；每个连接自己轮询，
 * Redis 的请求数随连接数线性增长。共用一个循环时，请求数只与节点数有关，延迟上限是轮询间隔。
 *
 * <p>订阅的顺序保证"不漏"：连接先登记（此后循环读到的条目都会交给它，在固定位置之前先缓冲），再读取 Stream 当前最新条目的 ID
 * 作为起点（{@link Subscription#pin}），起点之后的条目一定会被交付。新会话的读取位置取第一个连接的起点。Redis 不可用时登记照常
 * 完成，起点未知，恢复后从当时的最新位置继续；其间写入的持久化事件由 SSE 的对账从数据库补发（第 5 步），只在流上的增量事件丢失。
 *
 * <p>线程安全：读取循环是单线程的；每个会话的读取位置与交付在会话状态上加锁，每个订阅的交付与起点固定在订阅上加锁，
 * 所以对 {@link Sinks} 的发射是串行的。
 */
@Component
public class SessionStreamHub implements SmartLifecycle {

    /** Stream 不存在时的起点：从头读取。 */
    static final String FROM_START = "0-0";

    private static final Logger log = LoggerFactory.getLogger(SessionStreamHub.class);

    private final ReactiveRedisMessaging redis;
    private final SseProperties properties;
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private final AtomicBoolean failing = new AtomicBoolean();
    private volatile Disposable loop;

    /**
     * 创建读取入口。
     *
     * @param redis Redis 消息封装
     * @param properties SSE 配置
     */
    public SessionStreamHub(ReactiveRedisMessaging redis, SseProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 订阅一个会话的事件 Stream。返回的 {@code Mono} 在起点固定之后完成：此后写入 Stream 的条目都会出现在
     * {@link Subscription#entries()} 中（订阅取消前缓冲在订阅里）。
     *
     * @param sessionId 会话 ID
     * @return 订阅
     */
    public Mono<Subscription> open(String sessionId) {
        String key = EventStreamPublisher.streamKey(sessionId);
        return Mono.defer(() -> {
            Subscription subscription = new Subscription(key, properties.subscriberBuffer());
            streams.computeIfAbsent(key, k -> new StreamState()).subscribers.add(subscription);
            return redis.lastRecordId(key)
                    .defaultIfEmpty(FROM_START)
                    .onErrorResume(error -> {
                        log.warn("读取事件 Stream 的起点失败，待 Redis 恢复后从当时的位置继续 sessionId={}", sessionId, error);
                        return Mono.just("");
                    })
                    .map(start -> {
                        String pinned = start.isEmpty() ? null : start;
                        streams.computeIfPresent(key, (k, state) -> {
                            state.initialize(pinned);
                            return state;
                        });
                        subscription.pin(pinned);
                        return subscription;
                    })
                    .doOnCancel(subscription::close);
        });
    }

    /**
     * 执行一次读取并交付，测试与轮询循环共用。
     *
     * @return 读取（或失败已记录）后完成，不会以错误结束
     */
    Mono<Void> pollOnce() {
        Map<String, String> offsets = new LinkedHashMap<>();
        List<String> unpinned = new ArrayList<>();
        streams.forEach((key, state) -> {
            String cursor = state.cursor();
            if (cursor != null) {
                offsets.put(key, cursor);
            } else if (state.needsPin()) {
                unpinned.add(key);
            }
        });
        Mono<Void> pins = Flux.fromIterable(unpinned)
                .concatMap(key -> redis.lastRecordId(key)
                        .defaultIfEmpty(FROM_START)
                        .doOnNext(id -> {
                            StreamState state = streams.get(key);
                            if (state != null) {
                                state.initialize(id);
                            }
                        }))
                .then();
        Mono<Void> reads = redis.read(offsets, properties.readBatch())
                .doOnNext(this::dispatch)
                .then();
        return pins.then(reads)
                .doOnSuccess(done -> {
                    if (failing.compareAndSet(true, false)) {
                        log.info("事件 Stream 读取已恢复");
                    }
                })
                .onErrorResume(error -> {
                    if (failing.compareAndSet(false, true)) {
                        log.warn("读取事件 Stream 失败，SSE 由数据库对账补发，恢复前不再重复记录", error);
                    }
                    return Mono.empty();
                });
    }

    private void dispatch(StreamMessage message) {
        StreamState state = streams.get(message.stream());
        if (state != null) {
            state.deliver(message);
        }
    }

    private void unsubscribe(Subscription subscription) {
        streams.computeIfPresent(subscription.key, (key, state) -> {
            state.subscribers.remove(subscription);
            return state.subscribers.isEmpty() ? null : state;
        });
    }

    @Override
    public void start() {
        loop = Flux.interval(properties.pollInterval(), Schedulers.parallel())
                .onBackpressureDrop()
                .concatMap(tick -> pollOnce(), 1)
                .subscribe();
    }

    @Override
    public void stop() {
        Disposable current = loop;
        if (current != null) {
            current.dispose();
        }
        loop = null;
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    /**
     * 比较两个 Stream 条目 ID（{@code 毫秒-序号}）。
     *
     * @param left 条目 ID
     * @param right 条目 ID
     * @return 负数、0 或正数
     */
    static int compareIds(String left, String right) {
        int l = left.indexOf('-');
        int r = right.indexOf('-');
        int byTime = Long.compare(Long.parseLong(left, 0, l, 10), Long.parseLong(right, 0, r, 10));
        return byTime != 0
                ? byTime
                : Long.compare(
                        Long.parseLong(left, l + 1, left.length(), 10),
                        Long.parseLong(right, r + 1, right.length(), 10));
    }

    /** 一个会话 Stream 的读取位置与订阅者。 */
    private static final class StreamState {

        private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();
        private String cursor;
        private boolean pinRequested;

        synchronized String cursor() {
            return cursor;
        }

        /** 起点未知（Redis 曾不可用）时，由读取循环补上起点。 */
        synchronized boolean needsPin() {
            return cursor == null && pinRequested;
        }

        /** 用第一个固定的起点初始化读取位置；已有位置时不变，避免重复交付已读过的条目。 */
        synchronized void initialize(String start) {
            if (cursor == null) {
                if (start == null) {
                    pinRequested = true;
                } else {
                    cursor = start;
                }
            }
        }

        synchronized void deliver(StreamMessage message) {
            if (cursor != null && compareIds(message.recordId(), cursor) <= 0) {
                return;
            }
            cursor = message.recordId();
            for (Subscription subscription : subscribers) {
                subscription.offer(message);
            }
        }
    }

    /**
     * 一个 SSE 连接对会话 Stream 的订阅。取消 {@link #entries()} 的订阅即退订。
     */
    public final class Subscription {

        private final String key;
        private final Sinks.Many<StreamMessage> sink;
        private final List<StreamMessage> pending = new ArrayList<>();
        private boolean pinned;
        private String start;

        private Subscription(String key, int buffer) {
            this.key = key;
            this.sink = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer(Queues.<StreamMessage>get(buffer).get());
        }

        /**
         * 起点之后的条目，同一会话内按条目 ID 升序。缓冲超过上限时以错误结束，SSE 连接随之关闭，由客户端重连补发。
         *
         * @return 条目
         */
        public Flux<StreamMessage> entries() {
            return sink.asFlux().doFinally(signal -> close());
        }

        /** 退订，可重复调用。 */
        public void close() {
            unsubscribe(this);
            synchronized (this) {
                sink.tryEmitComplete();
            }
        }

        synchronized void pin(String startId) {
            start = startId;
            pinned = true;
            for (StreamMessage message : pending) {
                emitIfAfterStart(message);
            }
            pending.clear();
        }

        synchronized void offer(StreamMessage message) {
            if (pinned) {
                emitIfAfterStart(message);
            } else {
                pending.add(message);
            }
        }

        private void emitIfAfterStart(StreamMessage message) {
            if (start != null && compareIds(message.recordId(), start) <= 0) {
                return;
            }
            Sinks.EmitResult result = sink.tryEmitNext(message);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                sink.tryEmitError(new IllegalStateException("SSE 连接消费过慢，缓冲已满：" + key));
            }
        }
    }
}
