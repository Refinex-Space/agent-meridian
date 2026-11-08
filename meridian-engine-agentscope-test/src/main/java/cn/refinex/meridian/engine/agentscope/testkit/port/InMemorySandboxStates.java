package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.port.SandboxStatePort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * {@link SandboxStatePort} 的内存实现：会话 ID → 沙箱句柄。不记使用时间。
 */
public final class InMemorySandboxStates implements SandboxStatePort {

    private final Map<String, SandboxHandle> handles = new ConcurrentHashMap<>();

    @Override
    public Mono<Optional<SandboxHandle>> find(String sessionId) {
        return Mono.fromSupplier(() -> Optional.ofNullable(handles.get(sessionId)));
    }

    @Override
    public Mono<Void> save(String sessionId, SandboxHandle handle) {
        return Mono.fromRunnable(() -> handles.put(sessionId, handle));
    }

    @Override
    public Mono<Void> markUsed(String sessionId) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> clear(String sessionId) {
        return Mono.fromRunnable(() -> handles.remove(sessionId));
    }
}
