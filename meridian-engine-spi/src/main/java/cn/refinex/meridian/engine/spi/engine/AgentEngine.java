package cn.refinex.meridian.engine.spi.engine;

import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import java.time.Duration;
import java.util.Set;
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
     * 因为中断会触发 AgentScope 保存状态（架构设计「租约与 fencing」）。内核自己发现租约已失效时（状态写入或
     * {@code LeaseGuardMiddleware} 抛出 {@code LeaseLostException}），不输出任何事件，以该异常结束事件流：
     * 本节点已不拥有这一轮，终态由回收器或新的认领者给出（ADR-0026）。
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
     * 恢复确认选择 {@code retry} 时，在平台 Java 侧用原幂等键重新执行一个状态未知的工具调用，不经模型（架构设计「待处理动作」）。
     * 结果由调用方写进恢复说明。调用方在持有本轮租约时调用，工具的账本写入使用 {@code ctx} 中的租约 epoch。
     *
     * @param ctx 执行重试的这一轮的上下文
     * @param retry 要重新执行的调用
     * @return 执行结果；工具失败时 {@link ToolRetry.Result#succeeded()} 为 false，不以错误结束
     */
    Mono<ToolRetry.Result> retryToolCall(TurnContext ctx, ToolRetry retry);

    /**
     * 业务方声明的自定义工具不能使用的名字（架构设计「平台工具」中的保留工具名）：智能体装配后 Toolkit 中的全部工具名、全部平台
     * 工具名与 harness 仍然注册的工具名。另有 {@code mcp__} 前缀，调用方自行检查。
     *
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @return 保留工具名
     */
    Mono<Set<String>> reservedToolNames(String agentVersionId, String environmentId, long environmentRevision);

    /**
     * 节点下线：以 SYSTEM 来源中断全部执行并等待状态保存，最长等待 timeout（架构设计「崩溃恢复」中的"节点优雅下线"）。
     *
     * @param timeout 最长等待时间
     * @return 全部执行结束或超时后完成
     */
    Mono<Void> shutdown(Duration timeout);
}
