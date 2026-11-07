package cn.refinex.meridian.runtime.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * runtime 测试中 BlockHound 的放行规则，经 ServiceLoader 注册，{@code blockhound-junit-platform} 自动安装时加载。
 *
 * <p>放行三处，JDBC、日志、睡眠等真实的阻塞调用仍被拦截：
 * <ul>
 *   <li>{@code SecureRandom#nextBytes}。{@code NativePRNG} 每隔约 100 毫秒从 {@code /dev/urandom} 补充缓冲，BlockHound 把这次
 *       {@code FileInputStream} 读取判为阻塞；而 {@code /dev/urandom} 从不阻塞，读取是微秒级的系统调用。生成 ULID（例如
 *       {@code security.web} 的请求 ID 过滤器在 Netty 事件循环上为每个请求生成请求 ID）会走到这里。
 *   <li>Lettuce 的 {@code SharedLock}（7.5）：多个线程同时向共享连接发命令时，写锁的短暂竞争会在 Netty 事件循环上 park。这是
 *       Lettuce 有意的极短临界区；BlockHound 若在 {@code ReentrantLock} 排队时抛错，锁的等待队列会被破坏，之后同一 JVM 中所有
 *       Redis 命令都会卡住（T06 的事件扇出在并发写入时遇到）。
 *   <li>Hibernate Validator 的 {@code PlatformResourceBundleLocator#loadBundle}：第一次出现校验失败时从 jar 中读取消息资源包，
 *       之后缓存。WebFlux 在事件循环上校验请求体（T07），只有进程内的第一次失败会读文件。
 * </ul>
 */
public final class RuntimeBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("java.security.SecureRandom", "nextBytes");
        builder.allowBlockingCallsInside("io.lettuce.core.protocol.SharedLock", "incrementWriters");
        builder.allowBlockingCallsInside("io.lettuce.core.protocol.SharedLock", "lockWritersExclusive");
        builder.allowBlockingCallsInside(
                "org.hibernate.validator.resourceloading.PlatformResourceBundleLocator", "loadBundle");
    }
}
