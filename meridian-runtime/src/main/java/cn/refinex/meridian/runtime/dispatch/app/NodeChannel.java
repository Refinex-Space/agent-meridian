package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/**
 * 节点频道：经 Redis Pub/Sub 频道 {@code node:{node_id}} 通知持有某个线程租约的节点立即检查该轮次（架构设计「消息投递」中的
 * 「中断」）。消息内容是线程 ID。
 *
 * <p>通知只用来加速：中断请求（{@code turns.interrupt_requested_at}）与会话删除（轮次 {@code cancelled}）都以数据库为准，节点收到
 * 通知后立即对该线程续租一次（{@link LeaseRenewer#renewThread}），从数据库读出中断请求或发现租约已失去；通知丢失时由每 10 秒
 * 一次的续租兜底。
 *
 * <p>线程安全：无可变状态。
 */
@Component
public class NodeChannel {

    /** 频道名前缀，架构设计「消息投递」规定 {@code node:{lease_owner}}。 */
    public static final String PREFIX = "node:";

    /** 发布通知的等待上限：通知只用于加速，超时就放弃，由续租兜底。 */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(NodeChannel.class);

    private final ReactiveRedisMessaging redis;

    /**
     * 创建节点频道。
     *
     * @param redis Redis 消息封装
     */
    public NodeChannel(ReactiveRedisMessaging redis) {
        this.redis = redis;
    }

    /**
     * 节点的频道名。
     *
     * @param nodeId 节点 ID
     * @return {@code node:{node_id}}
     */
    public static String channel(String nodeId) {
        return PREFIX + nodeId;
    }

    /**
     * 当前事务提交后通知节点检查线程的轮次；没有活动事务时立即通知。通知在提交它的线程上发出并等待（最多 2 秒），调用方运行在
     * 虚拟线程上。
     *
     * @param nodeId 持有租约的节点；为空时（轮次从未被认领）不通知
     * @param threadId 线程 ID
     */
    public void notifyAfterCommit(String nodeId, String threadId) {
        if (nodeId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyNow(nodeId, threadId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyNow(nodeId, threadId);
            }
        });
    }

    /**
     * 订阅本节点的频道。Redis 不可用时按退避重新订阅，期间的通知丢失，由续租兜底。
     *
     * @param nodeId 本节点 ID
     * @return 线程 ID 的无限流；订阅取消时停止
     */
    public Flux<String> listen(String nodeId) {
        return redis.subscribe(channel(nodeId))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)));
    }

    private void notifyNow(String nodeId, String threadId) {
        try {
            redis.notify(channel(nodeId), threadId).block(PUBLISH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("通知节点失败，由续租兜底 nodeId={} threadId={}", nodeId, threadId, e);
        }
    }
}
