package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.common.concurrent.Blocking;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 批次级 checkpoint（{@code onActing}、{@code onReasoning}）：每批工具执行完成后保存一次智能体状态，进程被强杀时恢复从
 * 最近的批次继续，不必重放整轮（架构设计「崩溃恢复」，M0 验证 P3，ADR-0028）。
 *
 * <p>保存的时机是"一批工具执行完之后的下一次推理之前"，而不是 {@code onActing} 结束时：AgentScope 2.0.4 在
 * {@code onActing} 的事件流结束之后才把工具结果写进 {@code AgentState}（{@code notifyPostActingHook}），此时保存会得到只有
 * tool_use、没有 tool_result 的状态。下一次推理前，整批的结果都已在上下文中，tool_use 与 tool_result 一一配对。
 * 一批工具执行完就结束调用时（直接返回、挂起、请求停止），AgentScope 在调用结束时保存，这里不需要再保存。
 *
 * <p>保存调用 {@link ReActAgent#saveAgentState(RuntimeContext)}，经 {@code FencedAgentStateStore} 写入，受租约约束，并且是
 * 内容写入（更新 {@code content_epoch}）。写入是阻塞的 JDBC，经 {@link Blocking} 在虚拟线程上执行。
 *
 * <p>线程安全：无状态；每轮的标记在 {@link BatchCheckpoint} 中。
 */
public final class BatchCheckpointMiddleware implements MiddlewareBase {

    /**
     * 中间件顺序：在恢复说明（90）之内，使 checkpoint 包含已注入的恢复说明；T08 的 {@code SteerMiddleware} 同样必须排在本中间件
     * 之外，否则一次推理前注入的 steer 消息不在该次 checkpoint 中，强杀后从 checkpoint 续跑会丢失它。
     */
    public static final int ORDER = 80;

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        BatchCheckpoint checkpoint = BatchCheckpoint.of(ctx);
        Flux<AgentEvent> acting = next.apply(input);
        return checkpoint == null ? acting : acting.doOnComplete(checkpoint::batchCompleted);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (agent instanceof ReActAgent react && BatchCheckpoint.takePending(ctx)) {
                return Blocking.run(() -> react.saveAgentState(ctx)).thenMany(Flux.defer(() -> next.apply(input)));
            }
            return next.apply(input);
        });
    }
}
