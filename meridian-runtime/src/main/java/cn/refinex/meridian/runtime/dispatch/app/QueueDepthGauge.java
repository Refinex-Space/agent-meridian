package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 调度队列深度：{@code status='queued'} 的轮次数，指标 {@code meridian.dispatch.queue.depth}（Prometheus 中为
 * {@code meridian_dispatch_queue_depth}，架构设计「可观测性」）。
 *
 * <p>计数是一次数据库查询，不在每次抓取指标时执行：由后台任务按回收周期调用 {@link #refresh()}，量规读取最近一次的结果。
 * 线程安全：结果保存在原子变量中。
 */
@Component
public class QueueDepthGauge {

    private final TurnQueueRepository queue;
    private final AtomicLong depth = new AtomicLong();

    /**
     * 创建量规并注册。
     *
     * @param queue 轮次队列仓储
     * @param registry 指标注册表
     */
    public QueueDepthGauge(TurnQueueRepository queue, MeterRegistry registry) {
        this.queue = queue;
        Gauge.builder("meridian.dispatch.queue.depth", depth, AtomicLong::get)
                .description("等待认领的轮次数")
                .register(registry);
    }

    /** 重新统计队列深度。方法会阻塞。 */
    public void refresh() {
        depth.set(queue.countQueued());
    }

    /**
     * 最近一次统计的队列深度。
     *
     * @return 轮次数
     */
    public long current() {
        return depth.get();
    }
}
