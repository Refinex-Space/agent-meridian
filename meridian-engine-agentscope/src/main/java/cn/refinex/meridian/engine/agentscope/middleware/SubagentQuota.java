package cn.refinex.meridian.engine.agentscope.middleware;

import io.agentscope.core.agent.RuntimeContext;
import java.util.HashSet;
import java.util.Set;

/**
 * 一轮中 {@code spawn_subagent} 的拉起名额，由引擎每轮放进主线程的 {@code RuntimeContext}，{@link PolicyMiddleware} 在每批工具
 * 执行前按调用顺序登记（架构设计「子智能体」：每轮的拉起数量由 {@code PolicyMiddleware} 限制，默认 8 个）。
 *
 * <p>名额在执行前按批内顺序分配，所以并行执行的同一批调用中，被拒绝的总是排在后面的那些，与执行先后无关。被拒绝的调用照常进入
 * {@code spawn_subagent}，由工具给出拒绝结果：2.0.4 不发布 {@code onActing} 中间件自己合成的事件，中间件拒绝的调用没有
 * {@code agent.tool_result}（ADR-0034）。
 *
 * <p>线程安全：方法互斥。
 */
public final class SubagentQuota {

    private final Set<String> admitted = new HashSet<>();
    private final Set<String> rejected = new HashSet<>();

    /**
     * 从调用上下文读取本轮的名额。
     *
     * @param ctx 调用上下文；可为空
     * @return 名额；不是带名额的轮次（子线程、恢复确认的重试）时为空
     */
    public static SubagentQuota of(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(SubagentQuota.class);
    }

    /**
     * 为一个 {@code spawn_subagent} 调用分配名额。同一调用重复登记（确认之后整批再次执行）不重复占用。
     *
     * @param toolUseId 工具调用 ID
     * @param limit 每轮上限
     * @return 分到名额时为 true
     */
    public synchronized boolean admit(String toolUseId, int limit) {
        if (admitted.contains(toolUseId)) {
            return true;
        }
        if (admitted.size() >= limit) {
            rejected.add(toolUseId);
            return false;
        }
        admitted.add(toolUseId);
        return true;
    }

    /**
     * 调用是否因超出上限被拒绝。没有登记过的调用不算被拒绝。
     *
     * @param toolUseId 工具调用 ID
     * @return 被拒绝时为 true
     */
    public synchronized boolean rejected(String toolUseId) {
        return rejected.contains(toolUseId);
    }
}
