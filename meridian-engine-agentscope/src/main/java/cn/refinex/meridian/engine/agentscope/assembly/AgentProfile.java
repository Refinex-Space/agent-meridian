package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次装配的对象：主智能体，或主智能体版本中的某个子智能体，加上环境。装配器与各个提供者都按它工作，
 * 主智能体与子智能体走同一条装配路径。
 *
 * <p>子智能体使用自己的模型、提示词、工具集、技能与轮数，其余（压缩配置、知识库、环境）沿用父智能体版本；
 * 子智能体不启用计划模式、不能再拉起子智能体（架构设计「子智能体」）。
 *
 * @param agent 智能体版本配置；必填
 * @param subagent 子智能体配置，必须在 {@code agent.subagents()} 中；可为空，表示主智能体
 * @param environment 环境配置；必填
 */
public record AgentProfile(ResolvedAgentSpec agent, SubagentSpec subagent, EnvironmentSpec environment) {

    /**
     * 创建装配对象。
     *
     * @throws NullPointerException 缺少智能体版本或环境时
     * @throws IllegalArgumentException 子智能体不属于该智能体版本时
     */
    public AgentProfile {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(environment, "environment");
        if (subagent != null && !agent.subagents().contains(subagent)) {
            throw new IllegalArgumentException("子智能体不属于智能体版本 " + agent.agentVersionId() + "：" + subagent.name());
        }
    }

    /**
     * 主智能体的装配对象。
     *
     * @param agent 智能体版本配置
     * @param environment 环境配置
     * @return 装配对象
     */
    public static AgentProfile main(ResolvedAgentSpec agent, EnvironmentSpec environment) {
        return new AgentProfile(agent, null, environment);
    }

    /**
     * 子智能体的装配对象。
     *
     * @param agent 智能体版本配置
     * @param subagentName 子智能体名
     * @param environment 环境配置
     * @return 装配对象
     * @throws IllegalArgumentException 智能体版本中没有该子智能体时
     */
    public static AgentProfile subagent(ResolvedAgentSpec agent, String subagentName, EnvironmentSpec environment) {
        SubagentSpec subagent = agent.subagent(subagentName)
                .orElseThrow(() ->
                        new IllegalArgumentException("智能体版本 " + agent.agentVersionId() + " 中没有子智能体：" + subagentName));
        return new AgentProfile(agent, subagent, environment);
    }

    /**
     * 是否为子智能体。
     *
     * @return 子智能体时为 true
     */
    public boolean isSubagent() {
        return subagent != null;
    }

    /**
     * 实例缓存的键。
     *
     * @return 缓存键
     */
    public AssemblyKey key() {
        return new AssemblyKey(
                agent.agentVersionId(),
                environment.environmentId(),
                environment.revision(),
                subagent == null ? null : subagent.name());
    }

    /**
     * 智能体名：主智能体为智能体名，子智能体为子智能体名。
     *
     * @return 名字
     */
    public String name() {
        return subagent == null ? agent.name() : subagent.name();
    }

    /**
     * 模型引用。
     *
     * @return 模型引用
     */
    public ModelSpec model() {
        return subagent == null ? agent.model() : subagent.model();
    }

    /**
     * 智能体层的系统提示词。
     *
     * @return 系统提示词
     */
    public String systemPrompt() {
        return subagent == null ? agent.systemPrompt() : subagent.systemPrompt();
    }

    /**
     * 可用的工具名：主智能体为平台工具名，子智能体为平台工具名与 MCP 工具全名。
     *
     * @return 工具名集合
     */
    public Set<String> tools() {
        return subagent == null ? agent.tools() : subagent.tools();
    }

    /**
     * 可用技能。
     *
     * @return 技能引用
     */
    public List<SkillRef> skills() {
        return subagent == null ? agent.skills() : subagent.skills();
    }

    /**
     * 单次执行的最大推理次数。
     *
     * @return 最大推理次数
     */
    public int maxIters() {
        return subagent == null ? agent.maxIters() : subagent.maxIters();
    }

    /**
     * 是否启用计划模式；子智能体一律不启用。
     *
     * @return 启用时为 true
     */
    public boolean planMode() {
        return subagent == null && agent.planMode();
    }
}
