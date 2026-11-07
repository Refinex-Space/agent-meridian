package cn.refinex.meridian.common.error;

/**
 * 当前节点已失去线程的租约。抛出后本次执行的所有写入都必须放弃，见架构设计「租约与 fencing」。
 *
 * <p>它在 runtime 内部流转（状态存储、中间件、Worker），通常不会到达 HTTP 边界。
 */
public class LeaseLostException extends MeridianException {

    /**
     * 创建租约丢失异常。
     *
     * @param detail 说明，例如线程 ID 与期望的 epoch
     */
    public LeaseLostException(String detail) {
        super(CommonErrorCode.LEASE_LOST, detail);
    }
}
