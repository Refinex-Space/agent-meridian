package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.ToolPolicy;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 工具调用的纵深防御（架构设计「平台中间件」，ADR-0034）。工具策略分两处落地：
 *
 * <ul>
 *   <li>不在智能体工具集内的平台工具与 MCP 工具、{@code wait_async_results}：由 {@link ToolPolicy#denyRules} 生成整工具拒绝
 *       规则，引擎每轮安装权限时并入，权限引擎给出 DENIED 结果（有 {@code agent.tool_result} 事件与审计）。
 *   <li>非 {@code ToolBase} 工具不进权限引擎、一律被 2.0.4 放行：本中间件在执行前检查，只放行
 *       {@code load_skill_through_path}。装配自检已保证 Toolkit 中没有别的非 {@code ToolBase} 工具，所以命中这里说明装配被绕过，
 *       本轮以错误结束（失败关闭），不合成结果——2.0.4 不发布 {@code onActing} 中间件自己产生的事件。
 * </ul>
 *
 * <p>子智能体拉起数量的上限由 T13 加入。顺序 {@value #ORDER}：在 {@code BatchCheckpointMiddleware}（80）之内，在
 * {@code ToolSpanMiddleware}（50）之外。
 */
public final class PolicyMiddleware implements MiddlewareBase {

    /** 中间件顺序。 */
    public static final int ORDER = 70;

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            Toolkit toolkit = agent.getToolkit();
            for (ToolUseBlock call : input.toolCalls()) {
                AgentTool tool = toolkit == null ? null : lookup(toolkit, ctx, call.getName());
                if (tool != null
                        && !(tool instanceof ToolBase)
                        && !ToolPolicy.NON_TOOLBASE_ALLOWED.contains(call.getName())) {
                    return Flux.error(new IllegalStateException("工具 " + call.getName() + " 不是 ToolBase，不受权限规则约束，拒绝执行"));
                }
            }
            return next.apply(input);
        });
    }

    private static AgentTool lookup(Toolkit toolkit, RuntimeContext ctx, String name) {
        AgentTool tool = ctx == null || ctx.getToolRequestConfig() == null
                ? null
                : toolkit.getTool(name, ctx.getToolRequestConfig());
        return tool != null ? tool : toolkit.getTool(name);
    }
}
