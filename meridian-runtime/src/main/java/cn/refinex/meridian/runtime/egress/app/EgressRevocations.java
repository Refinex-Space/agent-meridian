package cn.refinex.meridian.runtime.egress.app;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

/**
 * 出网授权的失效：会话的 Pod IP 变化时，淘汰各节点缓存的该 IP 的授权（架构设计「沙箱出网」：沙箱删除时清空 Pod IP，同 IP 的新 Pod
 * 不继承旧授权）。
 *
 * <p>本节点在登记时立即淘汰一次、事务提交后再淘汰一次；提交后经 Redis Pub/Sub 频道 {@value #CHANNEL} 广播 IP，其他节点收到后淘汰。
 * 旧 IP 与新 IP 都要广播：错过旧 IP 广播的节点，在新会话认领到这个 IP 时还会再收到一次。广播失败或订阅中断期间，其他节点的旧条目
 * 最长保留到 30 秒过期，这个窗口记在 ADR 中。
 *
 * <p>线程安全：启停由 Spring 在一个线程上调用；{@link #revoke} 可并发调用。
 */
@Component
public class EgressRevocations implements SmartLifecycle {

    /** Pub/Sub 频道名，消息内容是 Pod IP。 */
    public static final String CHANNEL = "egress:revoke";

    /** 发布的等待上限：超时只记日志，其他节点由 30 秒过期兜底。 */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(EgressRevocations.class);

    private final EgressGrants grants;
    private final ReactiveRedisMessaging redis;
    private Disposable subscription;

    /**
     * 创建组件。
     *
     * @param grants 本节点的授权缓存
     * @param redis Redis 消息封装
     */
    public EgressRevocations(EgressGrants grants, ReactiveRedisMessaging redis) {
        this.grants = grants;
        this.redis = redis;
    }

    /**
     * 会话的 Pod IP 从 {@code before} 变为 {@code after} 时调用：二者相同时什么都不做，否则同 {@link #revoke}。
     *
     * @param before 原来的 Pod IP；可为空
     * @param after 新的 Pod IP；可为空
     */
    public void podIpChanged(String before, String after) {
        if (!Objects.equals(before, after)) {
            revoke(before, after);
        }
    }

    /**
     * 淘汰一组 IP 的授权（忽略空值与重复）。在改动 Pod IP 的事务中调用时，本节点立即淘汰，提交后再淘汰并广播；没有事务时立即淘汰并
     * 广播。
     *
     * @param ips Pod IP；元素可为空
     */
    public void revoke(String... ips) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String ip : ips) {
            if (ip != null) {
                distinct.add(ip);
            }
        }
        if (distinct.isEmpty()) {
            return;
        }
        distinct.forEach(grants::evict);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            distinct.forEach(this::revokeEverywhere);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                distinct.forEach(EgressRevocations.this::revokeEverywhere);
            }
        });
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = redis.subscribe(CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .subscribe(grants::evict, error -> log.error("出网授权失效频道的订阅意外结束", error));
        log.info("已订阅出网授权失效频道 channel={}", CHANNEL);
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return subscription != null;
    }

    private void revokeEverywhere(String ip) {
        grants.evict(ip);
        try {
            redis.notify(CHANNEL, ip).block(PUBLISH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("广播出网授权失效失败，其他节点的缓存最长 30 秒后过期 podIp={}", ip, e);
        }
    }
}
