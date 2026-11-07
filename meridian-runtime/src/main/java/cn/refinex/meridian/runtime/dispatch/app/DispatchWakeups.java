package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * 调度唤醒：轮次入队后经 Redis Pub/Sub 频道 {@code dispatch.wakeup} 通知所有节点，Worker 在通知与轮询之间取先到者
 * （架构设计「一轮执行的生命周期」「存储分工」中的唤醒与中断加速通知）。
 *
 * <p>{@link #signals()} 合并两路信号：频道上的通知与每 {@code pollInterval}（默认 1 秒）一次的轮询。Worker 按信号尝试认领，
 * 直到没有可执行的轮次；它忙于认领时到达的多个信号合并为一个（背压时只保留最新），不会堆积。Redis 不可用时通知这一路
 * 按退避重新订阅，轮询照常进行。
 *
 * <p>线程安全：无可变状态。
 */
@Component
public class DispatchWakeups implements WakeupPublisher {

    /** 唤醒频道，架构设计「一轮执行的生命周期」规定的名字。 */
    public static final String CHANNEL = "dispatch.wakeup";

    /** 发布通知的等待上限：通知只用于加速，超时就放弃，由轮询兜底。 */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(DispatchWakeups.class);

    private final ReactiveRedisMessaging redis;
    private final Duration pollInterval;
    private final Scheduler pollScheduler;

    /**
     * 创建唤醒组件，轮询在 Reactor 的 parallel 调度器上计时。
     *
     * @param redis Redis 消息封装
     * @param properties 调度配置，取轮询周期
     */
    @Autowired
    public DispatchWakeups(ReactiveRedisMessaging redis, DispatchProperties properties) {
        this(redis, properties.pollInterval(), Schedulers.parallel());
    }

    /**
     * 创建唤醒组件，指定轮询的计时调度器（测试用虚拟时间）。
     *
     * @param redis Redis 消息封装
     * @param pollInterval 轮询周期
     * @param pollScheduler 轮询的计时调度器
     */
    public DispatchWakeups(ReactiveRedisMessaging redis, Duration pollInterval, Scheduler pollScheduler) {
        this.redis = redis;
        this.pollInterval = pollInterval;
        this.pollScheduler = pollScheduler;
    }

    /**
     * 发布一次唤醒通知，在调用线程上等待发布完成（最多 2 秒）。调用方是入队事务提交后的回调，运行在虚拟线程上。
     */
    @Override
    public void publish() {
        try {
            redis.notify(CHANNEL, "turn").block(PUBLISH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("发布调度唤醒通知失败，由轮询兜底 channel={}", CHANNEL, e);
        }
    }

    /**
     * Worker 的认领信号：唤醒通知与轮询合并，背压时只保留最新一个。
     *
     * @return 无限的信号流，每个元素表示"现在去认领"；订阅取消时停止
     */
    public Flux<WakeupSource> signals() {
        Flux<WakeupSource> notified = redis.subscribe(CHANNEL)
                .map(message -> WakeupSource.NOTIFIED)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)));
        Flux<WakeupSource> polled = Flux.interval(pollInterval, pollScheduler).map(tick -> WakeupSource.POLLED);
        return Flux.merge(notified, polled).onBackpressureLatest();
    }

    /** 认领信号的来源。 */
    public enum WakeupSource {
        /** 收到 {@code dispatch.wakeup} 通知。 */
        NOTIFIED,
        /** 轮询周期到了。 */
        POLLED
    }
}
