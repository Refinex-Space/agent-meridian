package cn.refinex.meridian.engine.agentscope.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 智能体实例缓存与真实装配的组合（T03-A04）：缓存键、同键并发只装配一次、子智能体单独缓存、淘汰后归零才关闭。
 * 引用计数与淘汰的并发细节由 {@code meridian-engine-agentscope} 中的 {@code RefCountedCacheTest} 覆盖。
 */
class AgentInstanceCacheTest {

    private final AtomicLong nanos = new AtomicLong();
    private final TestAssembly assembly =
            TestAssembly.builder(ScriptedModel.builder().build()).build();
    private final AgentInstanceCache cache = new AgentInstanceCache(
            assembly.assembler(),
            AgentInstanceCache.MAXIMUM_SIZE,
            AgentInstanceCache.IDLE_TIMEOUT,
            nanos::get,
            Runnable::run);

    @Test
    void concurrentAcquireOfSameVersionAndEnvironmentAssemblesOnce() throws Exception {
        ResolvedAgentSpec spec = TestSpecs.agent().build();
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AgentHandle>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.acquire(spec, TestSpecs.noneEnvironment());
                }));
            }
            start.countDown();
            List<AgentHandle> handles = new ArrayList<>();
            for (Future<AgentHandle> f : futures) {
                handles.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(handles)
                    .extracting(AgentHandle::agent)
                    .containsOnly(handles.getFirst().agent());
            assertThat(assembly.modelFactory().requested()).as("只装配了一次").hasSize(1);
            handles.forEach(cache::release);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void keyIncludesEnvironmentRevisionAndSubagentName() {
        ResolvedAgentSpec spec = TestSpecs.agent()
                .tools("read_file", "spawn_subagent")
                .subagent(new SubagentSpec(
                        "reader",
                        "只读资料",
                        ModelSpec.of("mdl_test"),
                        "你只负责阅读。",
                        Set.of("read_file"),
                        List.of(),
                        PermissionPolicy.empty(),
                        5,
                        Duration.ofSeconds(60)))
                .build();
        EnvironmentSpec revision2 = new EnvironmentSpec("env_test_none", 2, EnvironmentSpec.Tier.NONE, null, List.of());

        AgentHandle main = cache.acquire(spec, TestSpecs.noneEnvironment());
        AgentHandle newRevision = cache.acquire(spec, revision2);
        AgentHandle sub = cache.acquireSubagent(spec, "reader", TestSpecs.noneEnvironment());

        assertThat(main.key()).isEqualTo(new AssemblyKey("agv_test", "env_test_none", 1, null));
        assertThat(newRevision.key()).isEqualTo(new AssemblyKey("agv_test", "env_test_none", 2, null));
        assertThat(sub.key()).isEqualTo(new AssemblyKey("agv_test", "env_test_none", 1, "reader"));
        assertThat(List.of(main.agent(), newRevision.agent(), sub.agent())).doesNotHaveDuplicates();
        assertThat(sub.agent().getName()).isEqualTo("reader");
        List.of(main, newRevision, sub).forEach(cache::release);
    }

    @Test
    void evictedInstanceIsClosedOnlyAfterTheTurnReleasesIt() {
        AgentHandle handle = cache.acquire(TestSpecs.agent().build(), TestSpecs.noneEnvironment());
        AssembledAgent evicted = handle.assembled();
        StaticToolProvider.TrackedResource resource =
                assembly.tools().resources().getFirst();

        nanos.addAndGet(AgentInstanceCache.IDLE_TIMEOUT.plusMinutes(1).toNanos());
        cache.cleanUp();
        assertThat(resource.closeCount()).as("还在使用，淘汰后不关闭").isZero();

        cache.release(handle);
        assertThat(resource.closeCount()).as("归还后关闭").isEqualTo(1);
        AgentHandle fresh = cache.acquire(TestSpecs.agent().build(), TestSpecs.noneEnvironment());
        assertThat(fresh.assembled()).isNotSameAs(evicted);
        cache.release(fresh);
    }

    @Test
    void evictingAgentVersionsRetiresOnlyTheirInstancesAndClosesAfterRelease() {
        ResolvedAgentSpec target =
                TestSpecs.agent().agentVersionId("agv_target").build();
        ResolvedAgentSpec other = TestSpecs.agent().agentVersionId("agv_other").build();
        AgentHandle busy = cache.acquire(target, TestSpecs.noneEnvironment());
        AgentHandle idle = cache.acquire(
                target, new EnvironmentSpec("env_test_none", 2, EnvironmentSpec.Tier.NONE, null, List.of()));
        cache.release(idle);
        AgentHandle untouched = cache.acquire(other, TestSpecs.noneEnvironment());
        List<StaticToolProvider.TrackedResource> resources = assembly.tools().resources();
        assertThat(resources).hasSize(3);

        cache.evictAgentVersions(Set.of("agv_target"));

        assertThat(resources.get(1).closeCount()).as("空闲的实例立即关闭").isEqualTo(1);
        assertThat(resources.get(0).closeCount()).as("正在执行的实例归还后才关闭").isZero();
        cache.release(busy);
        assertThat(resources.get(0).closeCount()).isEqualTo(1);
        assertThat(resources.get(2).closeCount()).as("其他版本不受影响").isZero();

        AgentHandle reassembled = cache.acquire(target, TestSpecs.noneEnvironment());
        assertThat(reassembled.assembled()).isNotSameAs(busy.assembled());
        AgentHandle same = cache.acquire(other, TestSpecs.noneEnvironment());
        assertThat(same.assembled()).isSameAs(untouched.assembled());
        List.of(reassembled, untouched, same).forEach(cache::release);
    }
}
