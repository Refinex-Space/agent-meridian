package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 订阅本节点的频道 {@code node:{node_id}}（{@link NodeChannel}），收到线程 ID 后立即对该线程续租一次，从而在一个检查点内送达中断
 * 请求、或在会话删除后立即放弃执行（架构设计「消息投递」）。
 *
 * <p>生命周期阶段与续租任务相同，节点下线等待执行结束期间仍在接收通知。线程安全：启停由 Spring 在一个线程上调用。
 */
public final class NodeChannelListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NodeChannelListener.class);

    private final NodeChannel channel;
    private final LeaseRenewer renewer;
    private final NodeId nodeId;
    private final int phase;
    private Disposable subscription;

    /**
     * 创建订阅组件。
     *
     * @param channel 节点频道
     * @param renewer 续租用例
     * @param nodeId 本节点 ID
     * @param phase 生命周期阶段
     */
    public NodeChannelListener(NodeChannel channel, LeaseRenewer renewer, NodeId nodeId, int phase) {
        this.channel = channel;
        this.renewer = renewer;
        this.nodeId = nodeId;
        this.phase = phase;
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = channel.listen(nodeId.value())
                .concatMap(threadId -> Blocking.run(() -> renewer.renewThread(threadId))
                        .onErrorResume(error -> {
                            log.warn("处理节点通知失败，由续租兜底 threadId={}", threadId, error);
                            return Mono.empty();
                        }))
                .subscribe(null, error -> log.error("节点频道的订阅意外结束", error));
        log.info("已订阅节点频道 channel={}", NodeChannel.channel(nodeId.value()));
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

    @Override
    public int getPhase() {
        return phase;
    }
}
