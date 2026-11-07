package cn.refinex.meridian.engine.spi.engine;

import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能体内核：执行一轮、中断、下线，签名按架构设计「内核适配层」。一期唯一的实现基于 AgentScope 2.0.4。
 *
 * <p>平台掌控会话语义，内核只执行一轮（架构原则第 1 条）。实现必须保证：每轮有且只有一个终态事件；出错时
 * 先输出 {@code session.error} 再输出终态事件；失去租约时只能被取消订阅，不能写入任何数据。
 */
public interface AgentEngine {

    /**
     * 执行一轮，输出平台事件流。订阅被取消即中止执行，不再写入任何数据。
     *
     * <p>订阅方（Worker）负责把事件持久化并发布；失去租约时 Worker 只取消订阅，不调用 {@link #interrupt}，
     * 因为中断会触发 AgentScope 保存状态（架构设计「租约与 fencing」）。
     *
     * @param ctx 本轮的上下文
     * @param input 本轮的输入，四种之一
     * @return 事件流，以一个终态事件结束
     */
    Flux<EngineEvent> runTurn(TurnContext ctx, TurnInput input);

    /**
     * 以 USER 来源中断本节点上正在执行的线程：先中断该轮登记的所有子线程，再中断父线程
     * （架构设计「消息投递」中的"中断"）。
     *
     * @param thread 要中断的线程
     * @return 本节点上有该线程的执行并已发出中断时为 true
     */
    boolean interrupt(ThreadRef thread);

    /**
     * 节点下线：以 SYSTEM 来源中断全部执行并等待状态保存，最长等待 timeout（架构设计「崩溃恢复」中的"节点优雅下线"）。
     *
     * @param timeout 最长等待时间
     * @return 全部执行结束或超时后完成
     */
    Mono<Void> shutdown(Duration timeout);
}
