package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * 从 {@link AgentInstanceCache} 租用的一个智能体实例。每轮开始时 {@code acquire}，结束时（包括异常结束）在
 * {@code finally} 中交给 {@link AgentInstanceCache#release} 归还，只能归还一次。
 */
public final class AgentHandle {

    private final RefCountedCache.Lease<AssemblyKey, AssembledAgent> lease;

    AgentHandle(RefCountedCache.Lease<AssemblyKey, AssembledAgent> lease) {
        this.lease = lease;
    }

    /**
     * 缓存键。
     *
     * @return 缓存键
     */
    public AssemblyKey key() {
        return lease.key();
    }

    /**
     * 装配好的实例。
     *
     * @return 实例
     */
    public AssembledAgent assembled() {
        return lease.value();
    }

    /**
     * 实例中的智能体。
     *
     * @return {@code HarnessAgent}
     */
    public HarnessAgent agent() {
        return lease.value().agent();
    }

    RefCountedCache.Lease<AssemblyKey, AssembledAgent> lease() {
        return lease;
    }
}
