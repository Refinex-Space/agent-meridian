package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 什么都不做的异步工具登记，装配时作为 {@code HarnessAgent.Builder.asyncToolRegistry(...)} 的入参。
 *
 * <p>只设置 {@link NoopMessageBus} 不够：AgentScope 2.0.4 在没有显式设置时会创建基于工作区文件系统的
 * {@code WorkspaceAsyncToolRegistry}，{@code InboxMiddleware} 每次推理前都调用它的 {@code findStale}，仍然每次读工作区。
 * 平台不启用 harness 的异步工具（不设置 {@code asyncToolTimeout}），所以这里永远没有记录。见 ADR-0024。
 *
 * <p>线程安全：无状态，全局共享一个实例。
 */
public final class NoopAsyncToolRegistry implements AsyncToolRegistry {

    /** 共享实例。 */
    public static final NoopAsyncToolRegistry INSTANCE = new NoopAsyncToolRegistry();

    private NoopAsyncToolRegistry() {}

    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> complete(String id, String result) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> fail(String id, String error) {
        return Mono.empty();
    }

    @Override
    public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> markTimeout(String id) {
        return Mono.empty();
    }
}
