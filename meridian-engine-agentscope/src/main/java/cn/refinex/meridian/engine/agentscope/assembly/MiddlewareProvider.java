package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;

/**
 * 提供平台中间件，装配时作为 {@code HarnessAgent.Builder.middlewares(...)} 的入参（架构设计「引擎装配」「平台中间件」）。
 *
 * <p>生产实现从 T04 起逐步补齐。主智能体挂载全部平台中间件；子智能体不含 {@code SteerMiddleware} 与
 * {@code KnowledgePrefetchMiddleware}（架构设计「子智能体」），由实现按 {@link AgentProfile#isSubagent()} 区分。
 * 中间件之间的执行顺序由各自的 {@code order()} 决定。
 */
@FunctionalInterface
public interface MiddlewareProvider {

    /**
     * 返回要挂载的平台中间件。
     *
     * @param profile 装配对象
     * @return 中间件；没有时为空列表
     */
    List<MiddlewareBase> middlewares(AgentProfile profile);
}
