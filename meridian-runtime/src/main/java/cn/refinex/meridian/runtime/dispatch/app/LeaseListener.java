package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import java.time.Instant;

/**
 * 执行方接收续租结果的回调，在 {@link LeaseHandle#listen} 时登记。回调在续租任务的虚拟线程上执行，必须很快返回
 * （例如只取消订阅或提交一个中断请求），不能阻塞续租。
 */
public interface LeaseListener {

    /**
     * 续租时读到了中断请求（{@code turns.interrupt_requested_at}），每个租约最多回调一次。执行方据此调用
     * {@code AgentEngine.interrupt}（架构设计「消息投递」）。
     *
     * @param requestedAt 中断请求的时间
     */
    void onInterruptRequested(Instant requestedAt);

    /**
     * 租约已经或即将失去，每个租约最多回调一次。执行方只能取消订阅，禁止调用 {@code interrupt}（架构设计「租约与 fencing」）。
     *
     * @param loss 原因
     */
    void onLeaseLost(LeaseLoss loss);
}
