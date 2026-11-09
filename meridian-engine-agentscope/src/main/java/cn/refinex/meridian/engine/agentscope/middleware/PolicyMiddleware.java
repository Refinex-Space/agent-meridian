package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.ToolPolicy;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>子智能体拉起数量的上限（架构设计「子智能体」，默认 {@value #DEFAULT_MAX_SUBAGENTS_PER_TURN}，可配置）：每批工具执行前按批内
 * 顺序为 {@code spawn_subagent} 调用在本轮的 {@link SubagentQuota} 中分配名额，超出的调用由 {@code spawn_subagent} 给出拒绝结果
 * （ADR-0040）。
 *
 * <p>顺序 {@value #ORDER}：在 {@code BatchCheckpointMiddleware}（80）之内，在 {@code ToolSpanMiddleware}（50）之外。
 */
public final class PolicyMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PolicyMiddleware.class);

    /** 中间件顺序。 */
    public static final int ORDER = 70;

    /** 每轮拉起子智能体的默认上限。架构设计「子智能体」规定默认 8 个。 */
    public static final int DEFAULT_MAX_SUBAGENTS_PER_TURN = 8;

    private final int maxSubagentsPerTurn;

    /** 创建中间件，拉起上限取默认值。 */
    public PolicyMiddleware() {
        this(DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    /**
     * 创建中间件。
     *
     * @param maxSubagentsPerTurn 每轮拉起子智能体的上限，至少 1
     * @throws IllegalArgumentException 上限小于 1 时
     */
    public PolicyMiddleware(int maxSubagentsPerTurn) {
        if (maxSubagentsPerTurn < 1) {
            throw new IllegalArgumentException("每轮拉起子智能体的上限至少为 1：" + maxSubagentsPerTurn);
        }
        this.maxSubagentsPerTurn = maxSubagentsPerTurn;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            Toolkit toolkit = agent.getToolkit();
            SubagentQuota quota = SubagentQuota.of(ctx);
            for (ToolUseBlock call : input.toolCalls()) {
                AgentTool tool = toolkit == null ? null : lookup(toolkit, ctx, call.getName());
                if (tool != null
                        && !(tool instanceof ToolBase)
                        && !ToolPolicy.NON_TOOLBASE_ALLOWED.contains(call.getName())) {
                    return Flux.error(new IllegalStateException("工具 " + call.getName() + " 不是 ToolBase，不受权限规则约束，拒绝执行"));
                }
                if (quota != null
                        && PlatformToolNames.SPAWN_SUBAGENT.equals(call.getName())
                        && !quota.admit(call.getId(), maxSubagentsPerTurn)) {
                    log.info("本轮拉起子智能体已达上限，拒绝调用 toolUseId={} limit={}", call.getId(), maxSubagentsPerTurn);
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
