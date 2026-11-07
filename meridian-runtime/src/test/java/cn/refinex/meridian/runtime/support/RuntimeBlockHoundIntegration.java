package cn.refinex.meridian.runtime.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * runtime 测试中 BlockHound 的放行规则，经 ServiceLoader 注册，{@code blockhound-junit-platform} 自动安装时加载。
 *
 * <p>只放行一处：{@code SecureRandom#nextBytes}。{@code NativePRNG} 每隔约 100 毫秒从 {@code /dev/urandom} 补充缓冲，BlockHound 把这次
 * {@code FileInputStream} 读取判为阻塞；而 {@code /dev/urandom} 从不阻塞，读取是微秒级的系统调用。生成 ULID（例如
 * {@code security.web} 的请求 ID 过滤器在 Netty 事件循环上为每个请求生成请求 ID）会走到这里。JDBC、日志、睡眠等真实的阻塞调用仍被拦截。
 */
public final class RuntimeBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("java.security.SecureRandom", "nextBytes");
    }
}
