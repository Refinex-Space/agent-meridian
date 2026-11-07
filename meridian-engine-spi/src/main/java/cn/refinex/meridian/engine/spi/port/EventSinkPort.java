package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.EngineEvent;
import reactor.core.publisher.Mono;

/**
 * 不经 {@code runTurn} 事件流、由内核直接写入的事件：子线程的事件（{@code SpawnSubagentTool}）与平台中间件的
 * 自定义事件，由 runtime 的 {@code stream} 实现，见架构设计「端口」「子智能体」。
 *
 * <p>与 Worker 写主线程事件相同：先写 PostgreSQL（带租约条件），再 XADD 到 Redis Stream。会话、轮次与租约 epoch
 * 从 Reactor Context 中的 {@code PlatformContext} 取（子线程使用父轮次的租约）。
 */
public interface EventSinkPort {

    /**
     * 追加一个事件。只在流上出现的事件不持久化，只发布到实时流。
     *
     * @param threadId 事件所属的线程（子线程事件为子线程 ID）
     * @param event 事件
     * @return 写入并发布后完成
     */
    Mono<Void> append(String threadId, EngineEvent event);
}
