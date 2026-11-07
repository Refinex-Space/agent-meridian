package cn.refinex.meridian.runtime.stream.domain;

import java.util.Objects;

/**
 * 事件写入附加的租约条件（架构设计「租约与 fencing」）。
 *
 * <ul>
 *   <li>{@link TurnLease}：Worker 与内核的写入。同一事务里锁定 {@code rt.turns} 中该轮次的行并校验
 *       {@code status='running' AND lease_owner = 本节点 AND lease_epoch = epoch}，不满足时什么都不写。
 *   <li>{@link None}：调用方发送的 {@code user.*} 事件，以及调度自己在已校验租约的事务里写入的事件（例如置回队列）。
 * </ul>
 */
public sealed interface WriteFence {

    /** 不附加租约条件。 */
    record None() implements WriteFence {}

    /**
     * 以轮次的租约为条件。节点取写入方所在的节点。
     *
     * @param turnId 持有租约的轮次（子线程事件为父轮次）；必填
     * @param epoch 本次执行的租约 epoch，大于 0
     */
    record TurnLease(String turnId, long epoch) implements WriteFence {

        /**
         * 创建租约条件。
         *
         * @throws NullPointerException 缺少轮次 ID 时
         * @throws IllegalArgumentException epoch 不大于 0 时
         */
        public TurnLease {
            Objects.requireNonNull(turnId, "turnId");
            if (epoch < 1) {
                throw new IllegalArgumentException("租约 epoch 必须大于 0：" + epoch);
            }
        }
    }

    /**
     * 不附加租约条件。
     *
     * @return 条件
     */
    static WriteFence none() {
        return new None();
    }

    /**
     * 以轮次的租约为条件。
     *
     * @param turnId 轮次 ID
     * @param epoch 租约 epoch
     * @return 条件
     */
    static WriteFence lease(String turnId, long epoch) {
        return new TurnLease(turnId, epoch);
    }
}
