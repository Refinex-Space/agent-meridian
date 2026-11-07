package cn.refinex.meridian.engine.agentscope.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 实例缓存的并发正确性（T03-A04）：同键并发租用只装配一次；引用计数未归零时淘汰不会关闭实例；归零后关闭。
 *
 * <p>时间用可控的 ticker 推进，淘汰回调与关闭在调用线程上同步执行，不依赖 {@code Thread.sleep}。
 */
class RefCountedCacheTest {

    private static final Duration IDLE = Duration.ofMinutes(30);

    private final AtomicLong nanos = new AtomicLong();
    /** 线程数不小于任何一个测试的并发调用者数：装配函数会等待全部调用者到达。 */
    private static final int THREADS = 16;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    void concurrentAcquireOfSameKeyAssemblesOnce() throws Exception {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        int callers = THREADS;
        CountDownLatch arrived = new CountDownLatch(callers);
        AtomicInteger loads = new AtomicInteger();
        List<Future<RefCountedCache.Lease<String, FakeInstance>>> futures = new ArrayList<>();

        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                arrived.countDown();
                return cache.acquire("agv_1", key -> {
                    loads.incrementAndGet();
                    // 等所有调用者都进入 acquire 再完成装配，保证它们与装配并发
                    await(arrived);
                    return new FakeInstance(key);
                });
            }));
        }
        List<RefCountedCache.Lease<String, FakeInstance>> leases = new ArrayList<>();
        for (Future<RefCountedCache.Lease<String, FakeInstance>> f : futures) {
            leases.add(f.get(10, TimeUnit.SECONDS));
        }

        assertThat(loads).hasValue(1);
        assertThat(leases)
                .extracting(RefCountedCache.Lease::value)
                .containsOnly(leases.getFirst().value());
        leases.forEach(cache::release);
        assertThat(leases.getFirst().value().closeCount).hasValue(0);
    }

    @Test
    void evictionWhileInUseDefersCloseUntilLastRelease() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        RefCountedCache.Lease<String, FakeInstance> first = cache.acquire("agv_1", FakeInstance::new);
        RefCountedCache.Lease<String, FakeInstance> second = cache.acquire("agv_1", FakeInstance::new);

        expireIdleEntries(cache);

        FakeInstance old = first.value();
        assertThat(old.closeCount).as("还有两个引用，淘汰后不关闭").hasValue(0);
        RefCountedCache.Lease<String, FakeInstance> fresh = cache.acquire("agv_1", FakeInstance::new);
        assertThat(fresh.value()).as("淘汰后的租用得到新装配的实例").isNotSameAs(old);

        cache.release(first);
        assertThat(old.closeCount).as("还有一个引用").hasValue(0);
        cache.release(second);
        assertThat(old.closeCount).as("引用归零后关闭，且只关闭一次").hasValue(1);
        assertThat(fresh.value().closeCount).hasValue(0);
        cache.release(fresh);
    }

    @Test
    void idleEntryIsClosedWhenEvictedWithZeroReferences() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire("agv_1", FakeInstance::new);
        cache.release(lease);

        nanos.addAndGet(IDLE.minusMinutes(1).toNanos());
        cache.cleanUp();
        assertThat(lease.value().closeCount).as("空闲未满 30 分钟不淘汰").hasValue(0);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        cache.cleanUp();
        assertThat(lease.value().closeCount).hasValue(1);
        assertThat(cache.size()).isZero();
    }

    @Test
    void releaseCountsAsAccessSoLongTurnsRestartTheIdleClock() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire("agv_1", FakeInstance::new);
        nanos.addAndGet(Duration.ofMinutes(20).toNanos());
        cache.release(lease);

        nanos.addAndGet(Duration.ofMinutes(20).toNanos());
        cache.cleanUp();

        assertThat(lease.value().closeCount).as("归还距今 20 分钟，未到空闲时间").hasValue(0);
        RefCountedCache.Lease<String, FakeInstance> again = cache.acquire("agv_1", FakeInstance::new);
        assertThat(again.value()).isSameAs(lease.value());
        cache.release(again);
    }

    @Test
    void capacityEvictsIdleEntriesAndClosesThem() {
        RefCountedCache<String, FakeInstance> cache = newCache(2);
        List<FakeInstance> instances = new ArrayList<>();
        for (String key : List.of("agv_1", "agv_2", "agv_3", "agv_4")) {
            RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire(key, FakeInstance::new);
            instances.add(lease.value());
            cache.release(lease);
        }
        cache.cleanUp();

        assertThat(cache.size()).isLessThanOrEqualTo(2);
        long closed = instances.stream().filter(i -> i.closeCount.get() == 1).count();
        assertThat(closed).isEqualTo(instances.size() - cache.size());
    }

    @Test
    void concurrentEvictionNeverClosesAnInstanceInUse() throws Exception {
        RefCountedCache<String, FakeInstance> cache = newCache(1);
        int workers = 8;
        int iterations = 2_000;
        AtomicBoolean running = new AtomicBoolean(true);
        Set<FakeInstance> seen = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < workers; w++) {
            String key = "agv_" + (w % 3);
            futures.add(pool.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire(key, FakeInstance::new);
                    try {
                        FakeInstance instance = lease.value();
                        seen.add(instance);
                        assertThat(instance.closeCount).as("租用期间实例不能被关闭").hasValue(0);
                        Thread.onSpinWait();
                        assertThat(instance.closeCount).as("租用期间实例不能被关闭").hasValue(0);
                    } finally {
                        cache.release(lease);
                    }
                }
                return null;
            }));
        }
        Future<?> evictor = pool.submit(() -> {
            // 容量为 1、三个键轮流租用，加上不断推进的时钟，淘汰与租用持续并发
            while (running.get()) {
                nanos.addAndGet(IDLE.plusSeconds(1).toNanos());
                cache.cleanUp();
            }
            return null;
        });
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        running.set(false);
        evictor.get(10, TimeUnit.SECONDS);
        cache.close();

        assertThat(seen.size()).as("并发淘汰确实发生，装配了多个实例").isGreaterThan(3);
        assertThat(seen)
                .allSatisfy(i -> assertThat(i.closeCount).as("关闭后每个实例恰好关闭一次").hasValue(1));
    }

    @Test
    void failedAssemblyIsNotCachedAndNextAcquireRetries() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);

        assertThatThrownBy(() -> cache.acquire("agv_1", key -> {
                    throw new IllegalStateException("装配失败");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("装配失败");

        RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire("agv_1", FakeInstance::new);
        assertThat(lease.value().key).isEqualTo("agv_1");
        cache.release(lease);
    }

    @Test
    void releasingTwiceIsRejected() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        RefCountedCache.Lease<String, FakeInstance> lease = cache.acquire("agv_1", FakeInstance::new);
        cache.release(lease);

        assertThatThrownBy(() -> cache.release(lease)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeClosesIdleInstancesNowAndBusyOnesOnRelease() {
        RefCountedCache<String, FakeInstance> cache = newCache(10);
        RefCountedCache.Lease<String, FakeInstance> idle = cache.acquire("agv_1", FakeInstance::new);
        cache.release(idle);
        RefCountedCache.Lease<String, FakeInstance> busy = cache.acquire("agv_2", FakeInstance::new);

        cache.close();

        assertThat(idle.value().closeCount).hasValue(1);
        assertThat(busy.value().closeCount).hasValue(0);
        cache.release(busy);
        assertThat(busy.value().closeCount).hasValue(1);
    }

    private RefCountedCache<String, FakeInstance> newCache(int maximumSize) {
        return new RefCountedCache<>(maximumSize, IDLE, nanos::get, Runnable::run);
    }

    private void expireIdleEntries(RefCountedCache<String, FakeInstance> cache) {
        nanos.addAndGet(IDLE.plusMinutes(1).toNanos());
        cache.cleanUp();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 记录关闭次数的实例替身。 */
    private static final class FakeInstance implements AutoCloseable {

        private final String key;
        private final AtomicInteger closeCount = new AtomicInteger();

        private FakeInstance(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
