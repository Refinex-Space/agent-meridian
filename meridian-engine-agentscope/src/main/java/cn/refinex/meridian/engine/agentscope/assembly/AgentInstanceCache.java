package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 智能体实例缓存，键为 {@code (agent_version_id, environment_id, environment_revision)}，子智能体再加子智能体名，
 * 规则见架构设计「引擎装配」：
 *
 * <ul>
 *   <li>容量上限 {@value #MAXIMUM_SIZE} 个，条目空闲 30 分钟后可以淘汰。
 *   <li>条目带引用计数：每轮开始时 {@link #acquire} 加一，结束时（包括异常结束）在 {@code finally} 中 {@link #release} 减一。
 *   <li>淘汰时引用计数归零才调用 {@code close()}；还有轮次在执行，就推迟到归零后再关闭，因为关闭会关掉 MCP 客户端。
 * </ul>
 *
 * <p>线程安全：所有方法都可以并发调用；同一个键并发 {@code acquire} 只装配一次。
 */
public final class AgentInstanceCache implements AutoCloseable {

    /** 缓存容量上限。架构设计「引擎装配」规定 500 个。 */
    public static final int MAXIMUM_SIZE = 500;

    /** 条目空闲多久后可以淘汰。架构设计「引擎装配」规定 30 分钟。 */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final AgentAssembler assembler;
    private final RefCountedCache<AssemblyKey, AssembledAgent> cache;

    /**
     * 创建缓存，使用架构设计规定的容量与空闲时间；淘汰回调与实例关闭在公共 ForkJoinPool 上执行。
     *
     * @param assembler 缓存未命中时使用的装配器
     */
    public AgentInstanceCache(AgentAssembler assembler) {
        this(assembler, MAXIMUM_SIZE, IDLE_TIMEOUT, Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

    AgentInstanceCache(
            AgentAssembler assembler, int maximumSize, Duration idleTimeout, Ticker ticker, Executor executor) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.cache = new RefCountedCache<>(maximumSize, idleTimeout, ticker, executor);
    }

    /**
     * 租用主智能体的实例，没有时装配。
     *
     * @param agent 智能体版本配置
     * @param environment 环境配置
     * @return 租用凭据，用完交给 {@link #release}
     */
    public AgentHandle acquire(ResolvedAgentSpec agent, EnvironmentSpec environment) {
        return acquire(AgentProfile.main(agent, environment));
    }

    /**
     * 租用子智能体的实例，没有时装配。
     *
     * @param agent 父智能体版本配置
     * @param subagentName 子智能体名
     * @param environment 环境配置
     * @return 租用凭据，用完交给 {@link #release}
     * @throws IllegalArgumentException 智能体版本中没有该子智能体时
     */
    public AgentHandle acquireSubagent(ResolvedAgentSpec agent, String subagentName, EnvironmentSpec environment) {
        return acquire(AgentProfile.subagent(agent, subagentName, environment));
    }

    /**
     * 归还实例。必须在 {@code finally} 中调用，每个凭据只能归还一次。
     *
     * @param handle 租用凭据
     * @throws IllegalStateException 重复归还时
     */
    public void release(AgentHandle handle) {
        cache.release(handle.lease());
    }

    /**
     * 移除这些智能体版本的全部实例（含子智能体）：空闲的立即关闭，正在使用的在归还后关闭，下一轮重新装配。配置失效时调用，例如租户提示词
     * 变了（它随实例缓存，D32）。
     *
     * @param agentVersionIds 智能体版本 ID
     */
    public void evictAgentVersions(Collection<String> agentVersionIds) {
        Set<String> targets = Set.copyOf(agentVersionIds);
        if (!targets.isEmpty()) {
            cache.invalidateIf(key -> targets.contains(key.agentVersionId()));
        }
    }

    /** 移除全部实例：空闲的立即关闭，正在使用的在归还后关闭。节点下线时调用。 */
    @Override
    public void close() {
        cache.close();
    }

    /** 立即执行到期淘汰，测试中用来推进状态。 */
    void cleanUp() {
        cache.cleanUp();
    }

    private AgentHandle acquire(AgentProfile profile) {
        return new AgentHandle(cache.acquire(profile.key(), key -> assembler.assemble(profile)));
    }
}
