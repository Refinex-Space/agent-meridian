package cn.refinex.meridian.runtime.dispatch.app;

import org.springframework.stereotype.Service;

/**
 * 租约回收器：每个节点每 5 秒运行一次（{@code DispatchProperties.reapInterval}），把租约过期的 {@code running} 轮次逐个置回
 * {@code queued}，直到没有过期的租约（架构设计「租约回收」）。
 *
 * <p>每个轮次一个事务（{@link TurnTransitions#reapNextExpired()}），{@code SKIP LOCKED} 使多个节点的回收器、以及正持有租约
 * 写入的执行方互不阻塞，同一轮次不会被重复回收。过期判断用注入的 {@code Clock}。
 */
@Service
public class LeaseReaper {

    private final TurnTransitions transitions;

    /**
     * 创建回收器。
     *
     * @param transitions 状态迁移用例
     */
    public LeaseReaper(TurnTransitions transitions) {
        this.transitions = transitions;
    }

    /**
     * 运行一个回收周期。
     *
     * @return 本周期回收的轮次数
     */
    public int reapExpired() {
        int reaped = 0;
        while (transitions.reapNextExpired().isPresent()) {
            reaped++;
        }
        return reaped;
    }
}
