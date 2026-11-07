package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import io.agentscope.harness.agent.tool.PlanModeTools;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个装配好的智能体实例：{@link HarnessAgent} 加上随它关闭的平台资源。
 *
 * <p>{@code HarnessAgent} 本身无状态，被同一缓存键下的所有会话共享（架构设计「引擎装配」）。实例由
 * {@link AgentInstanceCache} 管理生命周期，不要在别处关闭。
 *
 * <p>线程安全：不可变；{@link #close()} 只由缓存在引用计数归零后调用一次。
 */
public final class AssembledAgent implements AutoCloseable {

    /**
     * harness 装配后仍然注册的工具名（架构设计「平台工具」中的保留工具名），名字取自 AgentScope 2.0.4 的常量；
     * {@code wait_async_results} 没有公开常量，注册在 {@code WaitAsyncResultsTool} 的 {@code @Tool} 注解上。
     */
    public static final Set<String> HARNESS_TOOL_NAMES = Set.of(
            PlanModeTools.PLAN_ENTER,
            PlanModeTools.PLAN_WRITE,
            PlanModeTools.PLAN_EXIT,
            SkillLoadTool.TOOL_NAME,
            "wait_async_results");

    private final AgentProfile profile;
    private final HarnessAgent agent;
    private final List<AutoCloseable> resources;
    private final Set<String> reservedToolNames;

    AssembledAgent(AgentProfile profile, HarnessAgent agent, List<AutoCloseable> resources) {
        this.profile = profile;
        this.agent = agent;
        this.resources = List.copyOf(resources);
        Set<String> reserved = new TreeSet<>(agent.getToolkit().getToolNames());
        reserved.addAll(HARNESS_TOOL_NAMES);
        reserved.addAll(PlatformToolNames.ALL);
        this.reservedToolNames = Set.copyOf(reserved);
    }

    /**
     * 装配对象。
     *
     * @return 装配对象
     */
    public AgentProfile profile() {
        return profile;
    }

    /**
     * 装配得到的智能体。
     *
     * @return {@code HarnessAgent}
     */
    public HarnessAgent agent() {
        return agent;
    }

    /**
     * 业务方声明的自定义工具不能使用的名字：装配后 Toolkit 中的全部工具名，加上全部平台工具名与 harness 保留名。
     * 另有 {@code mcp__} 前缀，见 {@link #isReservedToolName(String)}。原因是 {@code ToolRequestConfig} 遇到同名时
     * 外部工具优先，会顶掉平台工具（架构设计「平台工具」）。
     *
     * @return 保留工具名
     */
    public Set<String> reservedToolNames() {
        return reservedToolNames;
    }

    /**
     * 判断一个名字是否被保留。
     *
     * @param toolName 工具名
     * @return 在保留名单中，或以 {@code mcp__} 开头时为 true
     */
    public boolean isReservedToolName(String toolName) {
        return reservedToolNames.contains(toolName) || PlatformToolNames.isMcpTool(toolName);
    }

    /**
     * 关闭智能体与平台资源：先关闭 {@code HarnessAgent}（含它自己拥有的 MCP 客户端），再按顺序关闭资源。
     * 某一步失败不影响后续步骤，失败以首个异常抛出，其余作为 suppressed。
     *
     * @throws Exception 任一步关闭失败时
     */
    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            agent.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
