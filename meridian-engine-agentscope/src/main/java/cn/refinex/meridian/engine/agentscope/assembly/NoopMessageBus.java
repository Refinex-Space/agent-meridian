package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 什么都不做的消息总线，装配时作为 {@code HarnessAgent.Builder.messageBus(...)} 的入参（架构设计「引擎装配」）。
 *
 * <p>不设置时 harness 使用工作区消息总线，每次推理前 {@code InboxMiddleware} 都会读一次工作区。平台的消息投递、
 * 子线程与后台任务都不经 harness 的总线，所以读出永远为空、写入直接丢弃。{@code wait_async_results} 工具仍会注册，
 * 由 {@code PolicyMiddleware} 直接返回合成的 DENIED 结果。
 *
 * <p>线程安全：无状态，全局共享一个实例。
 */
public final class NoopMessageBus implements MessageBus {

    /** 共享实例。 */
    public static final NoopMessageBus INSTANCE = new NoopMessageBus();

    private NoopMessageBus() {}

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.just("");
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.just(false);
    }

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.just("");
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.empty();
    }
}
