package cn.refinex.meridian.runtime.dispatch.app;

/** 一次认领的结果（架构设计「认领」）。只有 {@link Claimed} 改变了数据库，其余结果的认领事务都已回滚。 */
public sealed interface ClaimResult {

    /**
     * 认领成功，租约已登记到本节点。执行方必须在执行结束（含异常与取消）时调用 {@link LeaseHandle#release()}。
     *
     * @param lease 本节点的租约
     */
    record Claimed(LeaseHandle lease) implements ClaimResult {}

    /** 没有可执行的轮次：队列为空、都还没到最早认领时间、所属租户都已满额，或线程仍在本节点执行。 */
    record NoneAvailable() implements ClaimResult {}

    /**
     * 第 1 步选中了轮次，但第 2 步占用租户名额时被其他 Worker 抢先，租户已满额；事务已回滚，可以立即再试，其他租户的轮次不受影响。
     *
     * @param tenantId 满额的租户
     */
    record QuotaContended(String tenantId) implements ClaimResult {}

    /**
     * 选中的轮次所在线程在本节点仍有条目（上一次执行尚未结束），事务已回滚（架构设计「同节点重新认领」）。
     * 认领 SQL 已排除本节点持有的线程，只有条目在查询之后才登记时才会出现。
     *
     * @param threadId 线程 ID
     */
    record ThreadHeldOnNode(String threadId) implements ClaimResult {}
}
