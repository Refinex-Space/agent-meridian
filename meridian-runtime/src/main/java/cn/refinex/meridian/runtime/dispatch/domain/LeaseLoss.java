package cn.refinex.meridian.runtime.dispatch.domain;

/**
 * 执行方失去租约的原因，续租时发现（架构设计「租约」）。两种情况执行方都只能取消订阅，禁止调用 {@code interrupt}
 * （架构设计「租约与 fencing」）。
 */
public enum LeaseLoss {
    /** 续租更新了 0 行：轮次已被回收或被其他节点认领，租约确定已经失去。 */
    REVOKED,
    /** 续租连续失败（例如数据库不可用）超过阈值，默认 15 秒：租约可能即将过期，为避免与新的持有者并行执行而主动放弃。 */
    RENEWAL_TIMEOUT
}
