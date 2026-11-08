package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 智能体实例的工具策略（架构设计「平台工具」「平台中间件」中 {@code PolicyMiddleware} 的职责，ADR-0034）：
 *
 * <ul>
 *   <li>装配自检（登记事项 D02）：AgentScope 的权限引擎只对 {@code ToolBase} 生效，非 {@code ToolBase} 工具一律放行，所以
 *       Toolkit 中只允许 {@value SkillLoadTool#TOOL_NAME} 一个非 {@code ToolBase} 工具。违反时装配失败；runtime 启动时用探测
 *       装配跑一次（{@code AgentAssembler.selfCheck}），所以违反时启动失败。"平台工具继承 {@link PlatformTool}"由
 *       {@code meridian-arch-tests} 的规则 13 检查生产代码（测试替身可以用平台工具名注册普通 {@code ToolBase}）。
 *   <li>策略拒绝规则：{@code wait_async_results}（2.0.4 中它由 {@code @Tool} 方法包装为 {@code ReflectiveFunctionTool}，是
 *       {@code ToolBase}，会进权限引擎；平台没有异步工具，一律拒绝），以及注册了但不在智能体工具集内的平台工具与 MCP 工具。
 *       引擎每轮安装权限时把它们并入合成结果，由权限引擎给出 DENIED 结果——这样事件流中有 {@code agent.tool_result}、审计中有
 *       {@code permission.decided}；2.0.4 不发布 {@code onActing} 中间件自己产生的事件，中间件合成的结果进不了事件流。
 * </ul>
 */
public final class ToolPolicy {

    /** harness 注册的、不能关闭的异步结果工具名（2.0.4 没有公开常量，名字在 {@code WaitAsyncResultsTool} 的注解上）。 */
    public static final String WAIT_ASYNC_RESULTS = "wait_async_results";

    /** 允许出现在 Toolkit 中、且执行时放行的非 {@code ToolBase} 工具：只有 {@code load_skill_through_path}。 */
    public static final Set<String> NON_TOOLBASE_ALLOWED = Set.of(SkillLoadTool.TOOL_NAME);

    private ToolPolicy() {}

    /**
     * 检查 Toolkit。
     *
     * @param toolkit 装配后的 Toolkit
     * @throws IllegalStateException 有白名单外的非 {@code ToolBase} 工具时
     */
    public static void verify(Toolkit toolkit) {
        Set<String> violations = new TreeSet<>();
        for (String name : toolkit.getToolNames()) {
            AgentTool tool = toolkit.getTool(name);
            if (!(tool instanceof ToolBase) && !NON_TOOLBASE_ALLOWED.contains(name)) {
                violations.add(name);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Toolkit 自检失败：白名单外的非 ToolBase 工具不受权限规则约束：" + String.join("，", violations));
        }
    }

    /**
     * 智能体实例的策略拒绝规则，层为 {@code platform}，按工具名排序。
     *
     * @param profile 装配对象
     * @param toolkit 装配后的 Toolkit
     * @return 整工具的拒绝规则
     */
    public static List<PermissionRule> denyRules(AgentProfile profile, Toolkit toolkit) {
        List<PermissionRule> rules = new ArrayList<>();
        for (String name : new TreeSet<>(toolkit.getToolNames())) {
            boolean outsideToolSet = (PlatformToolNames.ALL.contains(name) || PlatformToolNames.isMcpTool(name))
                    && !granted(profile, name);
            if (name.equals(WAIT_ASYNC_RESULTS) || outsideToolSet) {
                rules.add(new PermissionRule(name, null, PermissionBehavior.DENY, PolicyLayer.PLATFORM));
            }
        }
        return List.copyOf(rules);
    }

    private static boolean granted(AgentProfile profile, String name) {
        return profile.isSubagent()
                ? profile.tools().contains(name)
                : profile.agent().grants(name);
    }
}
