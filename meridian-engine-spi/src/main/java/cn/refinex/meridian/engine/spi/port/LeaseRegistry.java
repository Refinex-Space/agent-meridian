package cn.refinex.meridian.engine.spi.port;

import java.util.Objects;
import java.util.Optional;

/**
 * 查询线程在本节点持有的租约，供 {@code FencedAgentStateStore} 与 {@code LeaseGuardMiddleware} 使用，
 * 由 runtime 的 {@code dispatch} 实现，见架构设计「端口」「租约与 fencing」。
 *
 * <p>AgentScope 的状态存储接口拿不到调用上下文，只能按线程 ID 查这里。所以同一节点上，一个线程的上一次执行
 * 在 {@code doFinally} 完成之前不能被再次认领，条目也在执行结束时才移除，避免旧执行的残余写入借用新 epoch。
 * 条目的登记与移除由 Worker 完成，不经本端口。
 *
 * <p>线程安全：实现必须支持并发读写。方法都是内存操作，不阻塞。
 */
public interface LeaseRegistry {

    /**
     * 查询线程当前的租约。子线程登记过父线程后，返回父线程的租约（子线程的状态写入使用父轮次的租约）。
     *
     * @param threadId 线程 ID（主线程或已登记的子线程）
     * @return 租约；本节点没有该线程的执行时为空
     */
    Optional<Lease> find(String threadId);

    /**
     * 登记子线程，此后子线程的租约查询返回父线程的租约。由 {@code spawn_subagent} 在创建子线程后调用。
     *
     * @param childThreadId 子线程 ID
     * @param parentThreadId 父线程 ID，必须在本节点持有租约
     * @throws IllegalStateException 父线程不在本节点执行时
     */
    void attachChildThread(String childThreadId, String parentThreadId);

    /**
     * 取消子线程的登记，在子智能体执行结束（含异常与取消）时调用。重复调用无副作用。
     *
     * @param childThreadId 子线程 ID
     */
    void detachChildThread(String childThreadId);

    /**
     * 标记线程正在安装权限上下文。安装写入不是"内容写入"，状态存储据此不更新 {@code content_epoch}
     * （架构设计「租约与 fencing」「崩溃恢复」）。
     *
     * @param threadId 线程 ID
     * @param installing 开始安装时为 true，安装结束（在 {@code finally} 中）为 false
     */
    void markPermissionInstall(String threadId, boolean installing);

    /**
     * 一个线程在本节点持有的租约。
     *
     * @param leaseThreadId 持有租约的线程：主线程为自身，子线程为父线程；必填
     * @param turnId 租约所属的轮次 ID（{@code trn_…}）；必填
     * @param epoch 租约 epoch，即 {@code rt.turns.lease_epoch}，大于 0
     * @param permissionInstall 是否正在安装权限上下文
     */
    record Lease(String leaseThreadId, String turnId, long epoch, boolean permissionInstall) {

        /**
         * 创建租约。
         *
         * @throws NullPointerException 缺少线程 ID 或轮次 ID 时
         * @throws IllegalArgumentException epoch 不大于 0 时
         */
        public Lease {
            Objects.requireNonNull(leaseThreadId, "leaseThreadId");
            Objects.requireNonNull(turnId, "turnId");
            if (epoch < 1) {
                throw new IllegalArgumentException("租约 epoch 必须大于 0：" + epoch);
            }
        }
    }
}
