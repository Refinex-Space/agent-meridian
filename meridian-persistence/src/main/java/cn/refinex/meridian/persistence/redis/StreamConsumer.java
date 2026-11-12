package cn.refinex.meridian.persistence.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 按消费组消费一个 Redis Stream 的后台循环（ADR-0048 第 7 条），随 Spring 容器启停：control-plane 的用量与审计消费者。
 *
 * <p>每轮：每隔 {@link Settings#claimInterval()} 认领空闲超过 {@link Settings#claimIdle()} 的待确认消息（别的实例崩溃留下的），再
 * 读取新消息（没有时最多等 {@link Settings#block()}）。一批消息交给 {@link Handler#handle} 在一个事务中处理，成功后确认；整批失败时
 * 逐条重试，逐条仍失败的：
 *
 * <ul>
 *   <li>瞬时错误（数据库或 Redis 连不上、超时）不确认，等恢复后由认领重投；
 *   <li>其他错误交给 {@link Handler#deadLetter}，处置成功才确认，一条坏消息不会挡住后面的消息。
 * </ul>
 *
 * 投递是至少一次，{@link Handler#handle} 必须幂等。每隔 {@link Settings#trimInterval()} 裁剪全部消费组都已确认的条目（登记事项 D04）。
 *
 * <p>多实例并发运行：同组内每条新消息只投递给一个消费者，消费者名在组内必须唯一（通常取实例 ID）。线程安全：生命周期方法同步；
 * {@link #pollOnce} 供测试直接驱动，不要与后台循环同时调用。
 */
public final class StreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    /** 停止时等待当前一轮结束的上限。 */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    /** 一轮出错后的等待时间，避免依赖不可用时空转。 */
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final RedisMessaging messaging;
    private final String stream;
    private final String group;
    private final String consumer;
    private final Handler handler;
    private final Settings settings;
    private final Clock clock;
    private Instant lastClaim = Instant.MIN;
    private Instant lastTrim = Instant.MIN;
    private volatile boolean running;
    private Thread worker;

    /**
     * 创建消费者。
     *
     * @param messaging Redis 客户端
     * @param stream Stream 的键
     * @param group 消费组名
     * @param consumer 消费者名，组内唯一
     * @param handler 处理逻辑
     * @param settings 批量与时间参数
     * @param clock 时钟，决定认领与裁剪的间隔
     */
    public StreamConsumer(
            RedisMessaging messaging,
            String stream,
            String group,
            String consumer,
            Handler handler,
            Settings settings,
            Clock clock) {
        this.messaging = Objects.requireNonNull(messaging, "messaging");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.group = Objects.requireNonNull(group, "group");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 执行一轮：按间隔认领重投、读取新消息并处理、按间隔裁剪。
     *
     * @param block 没有新消息时最多等待的时长；为零表示不等待
     * @return 本轮确认的消息数
     */
    public int pollOnce(Duration block) {
        messaging.ensureGroup(stream, group);
        int acked = 0;
        Instant now = clock.instant();
        if (!now.isBefore(lastClaim.plus(settings.claimInterval()))) {
            lastClaim = now;
            List<StreamMessage> claimed =
                    messaging.claimIdle(stream, group, consumer, settings.claimIdle(), settings.batchSize());
            if (!claimed.isEmpty()) {
                log.info("认领其他消费者未确认的消息 stream={} count={}", stream, claimed.size());
                acked += process(claimed);
            }
        }
        List<StreamMessage> fresh = messaging.readGroup(stream, group, consumer, settings.batchSize(), block);
        acked += process(fresh);
        if (!now.isBefore(lastTrim.plus(settings.trimInterval()))) {
            lastTrim = now;
            long removed = messaging.trimAcknowledged(stream);
            if (removed > 0) {
                log.debug("裁剪已确认的条目 stream={} removed={}", stream, removed);
            }
        }
        return acked;
    }

    private int process(List<StreamMessage> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            handler.handle(batch);
            return (int) messaging.ack(stream, group, ids(batch));
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                throw e;
            }
            log.warn("一批消息处理失败，逐条重试 stream={} count={}", stream, batch.size(), e);
        }
        List<String> done = new ArrayList<>(batch.size());
        for (StreamMessage message : batch) {
            try {
                handler.handle(List.of(message));
                done.add(message.recordId());
            } catch (RuntimeException e) {
                if (isTransient(e)) {
                    messaging.ack(stream, group, done);
                    throw e;
                }
                if (handler.deadLetter(message, e)) {
                    log.error("消息无法处理，已转入死信 stream={} recordId={}", stream, message.recordId(), e);
                    done.add(message.recordId());
                }
            }
        }
        return (int) messaging.ack(stream, group, done);
    }

    /**
     * 是否为瞬时错误：数据库或 Redis 不可用、超时、事务无法开启。瞬时错误的消息不进死信，恢复后重投。
     *
     * @param error 异常
     * @return 原因链上有瞬时错误时为 true
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof QueryTimeoutException
                    || t instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }

    private static List<String> ids(List<StreamMessage> batch) {
        return batch.stream().map(StreamMessage::recordId).toList();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("meridian-stream-" + stream).start(this::loop);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (!worker.join(SHUTDOWN_TIMEOUT)) {
                log.warn("Stream 消费者未在超时内结束 stream={}", stream);
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        }
        worker = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce(settings.block());
            } catch (RuntimeException e) {
                log.warn("Stream 消费出错，稍后重试 stream={}", stream, e);
                try {
                    Thread.sleep(ERROR_BACKOFF);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 消息的处理逻辑。 */
    public interface Handler {

        /**
         * 在一个事务中处理一批消息：全部成功才返回，否则抛异常（整批回滚）。必须幂等：同一条消息可能被投递多次。
         *
         * @param messages 消息，至少一条
         */
        void handle(List<StreamMessage> messages);

        /**
         * 处置一条无法处理的消息（例如写入死信表）。
         *
         * @param message 消息
         * @param error 处理它时的异常
         * @return 已妥善保存、可以确认时为 true；返回 false 或抛异常时消息留在待确认列表中，之后重投
         */
        boolean deadLetter(StreamMessage message, RuntimeException error);
    }

    /**
     * 批量与时间参数。
     *
     * @param batchSize 每次读取与认领的条数
     * @param block 没有新消息时最多等待的时长
     * @param claimIdle 待确认消息空闲多久后视为原消费者失效、可以认领
     * @param claimInterval 两次认领检查的间隔
     * @param trimInterval 两次裁剪的间隔
     */
    public record Settings(
            int batchSize, Duration block, Duration claimIdle, Duration claimInterval, Duration trimInterval) {

        /** 默认参数：每批 100 条，等待 2 秒，空闲 60 秒认领，每 10 秒检查认领，每分钟裁剪。 */
        public static final Settings DEFAULT = new Settings(
                100, Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofMinutes(1));

        /**
         * 校验参数。
         *
         * @throws IllegalArgumentException 批量不大于 0 时
         */
        public Settings {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须大于 0：" + batchSize);
            }
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(claimIdle, "claimIdle");
            Objects.requireNonNull(claimInterval, "claimInterval");
            Objects.requireNonNull(trimInterval, "trimInterval");
        }
    }
}
