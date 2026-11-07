package cn.refinex.meridian.engine.agentscope.assembly;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带引用计数的实例缓存：容量与空闲淘汰交给 Caffeine，关闭时机由引用计数决定（架构设计「引擎装配」的缓存规则）。
 *
 * <p>规则：
 * <ul>
 *   <li>同一个键并发 {@link #acquire} 只装配一次：Caffeine 原子地放入一个轻量条目，装配在条目自己的锁里进行，
 *       不占用 Caffeine 内部的分段锁，装配慢也不阻塞其他键。
 *   <li>条目被淘汰（容量、空闲、显式移除）后标记为退役，之后的 {@code acquire} 会装配新实例；退役条目在引用计数
 *       归零时才关闭实例。所以淘汰与 {@code acquire} 并发时，不会关闭一个正在被使用的实例。
 *   <li>关闭在给定的执行器上进行，不占用调用 {@link #release} 的线程（它可能是 Reactor 线程）。
 * </ul>
 *
 * <p>线程安全：所有方法都可以并发调用。
 *
 * @param <K> 键
 * @param <V> 实例
 */
final class RefCountedCache<K, V extends AutoCloseable> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RefCountedCache.class);

    private final Cache<K, Entry<K, V>> cache;
    private final Executor closeExecutor;

    /**
     * 创建缓存。
     *
     * @param maximumSize 最多缓存的条目数
     * @param idleTimeout 条目最后一次被访问后多久可以淘汰
     * @param ticker 时间源，测试中用可控的时钟
     * @param executor 执行淘汰回调与关闭实例的执行器
     */
    RefCountedCache(int maximumSize, Duration idleTimeout, Ticker ticker, Executor executor) {
        this.closeExecutor = executor;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterAccess(idleTimeout)
                .ticker(ticker)
                .executor(executor)
                .<K, Entry<K, V>>removalListener((key, entry, cause) -> {
                    if (entry != null) {
                        entry.retire();
                    }
                })
                .build();
    }

    /**
     * 取得键对应的实例并把引用计数加一，没有时用 {@code loader} 装配。用完必须调用 {@link #release}，通常在 {@code finally} 中。
     *
     * @param key 键
     * @param loader 装配函数，同一个键并发调用时只执行一次
     * @return 租用凭据
     * @throws RuntimeException 装配失败时原样抛出；失败的条目被移除，下一次调用重新装配
     */
    Lease<K, V> acquire(K key, Function<? super K, ? extends V> loader) {
        while (true) {
            Entry<K, V> entry = cache.get(key, k -> new Entry<>(k, closeExecutor));
            if (!entry.retain()) {
                // 拿到的条目恰好在这期间被淘汰并退役，它已不在映射中，重新取一次会得到新条目
                cache.asMap().remove(key, entry);
                continue;
            }
            try {
                return new Lease<>(entry, entry.load(loader));
            } catch (RuntimeException | Error e) {
                cache.asMap().remove(key, entry);
                entry.release();
                throw e;
            }
        }
    }

    /**
     * 归还实例，引用计数减一；条目已退役且计数归零时关闭实例。
     *
     * @param lease 租用凭据
     * @throws IllegalStateException 同一个凭据重复归还时
     */
    void release(Lease<K, V> lease) {
        lease.markReleased();
        // 视作一次访问：一轮执行得再久，结束后也重新计算空闲时间
        cache.getIfPresent(lease.entry.key);
        lease.entry.release();
    }

    /** 立即执行到期淘汰等维护工作。Caffeine 的维护是惰性的，测试中用它推进状态。 */
    void cleanUp() {
        cache.cleanUp();
    }

    /**
     * 当前缓存的条目数（近似值）。
     *
     * @return 条目数
     */
    long size() {
        return cache.estimatedSize();
    }

    /** 移除全部条目：空闲的实例立即关闭，正在使用的实例在归还后关闭。 */
    @Override
    public void close() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    /**
     * 一次租用：持有条目与实例，归还时使用。
     *
     * @param <K> 键
     * @param <V> 实例
     */
    static final class Lease<K, V extends AutoCloseable> {

        private final Entry<K, V> entry;
        private final V value;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Entry<K, V> entry, V value) {
            this.entry = entry;
            this.value = value;
        }

        K key() {
            return entry.key;
        }

        V value() {
            return value;
        }

        private void markReleased() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException("实例已经归还过：" + entry.key);
            }
        }
    }

    /** 缓存条目：一个可能尚未装配的实例，及其引用计数与退役标记。 */
    private static final class Entry<K, V extends AutoCloseable> {

        private final K key;
        private final Executor closeExecutor;
        private final Object loadLock = new Object();

        /** 以下字段由 {@code this} 的监视器保护。 */
        private V value;

        private RuntimeException loadFailure;
        private Error loadError;
        private int refCount;
        private boolean retired;
        private boolean closed;

        private Entry(K key, Executor closeExecutor) {
            this.key = key;
            this.closeExecutor = closeExecutor;
        }

        private synchronized boolean retain() {
            if (retired) {
                return false;
            }
            refCount++;
            return true;
        }

        /**
         * 返回实例，第一次调用时装配。并发调用者等在装配锁上，装配失败时都得到同一个异常。
         * 调用者已经持有引用，装配期间条目不会被关闭。
         */
        private V load(Function<? super K, ? extends V> loader) {
            synchronized (loadLock) {
                synchronized (this) {
                    if (value != null) {
                        return value;
                    }
                    if (loadFailure != null) {
                        throw loadFailure;
                    }
                    if (loadError != null) {
                        throw loadError;
                    }
                }
                try {
                    V created = Objects.requireNonNull(loader.apply(key), "装配结果不能为空");
                    synchronized (this) {
                        value = created;
                    }
                    return created;
                } catch (RuntimeException e) {
                    synchronized (this) {
                        loadFailure = e;
                    }
                    throw e;
                } catch (Error e) {
                    synchronized (this) {
                        loadError = e;
                    }
                    throw e;
                }
            }
        }

        private void release() {
            V toClose;
            synchronized (this) {
                refCount--;
                toClose = closeIfIdle();
            }
            scheduleClose(toClose);
        }

        private void retire() {
            V toClose;
            synchronized (this) {
                retired = true;
                toClose = closeIfIdle();
            }
            scheduleClose(toClose);
        }

        /** 已退役且没有引用时标记为已关闭，返回要关闭的实例；否则返回 null。调用方持有监视器。 */
        private V closeIfIdle() {
            if (retired && refCount == 0 && !closed) {
                closed = true;
                return value;
            }
            return null;
        }

        private void scheduleClose(V toClose) {
            if (toClose == null) {
                return;
            }
            closeExecutor.execute(() -> {
                try {
                    toClose.close();
                    log.debug("缓存实例已关闭 key={}", key);
                } catch (Exception e) {
                    log.warn("缓存实例关闭失败 key={}", key, e);
                }
            });
        }
    }
}
