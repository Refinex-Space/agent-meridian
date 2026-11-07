package cn.refinex.meridian.engine.agentscope.assembly;

import java.util.Objects;

/**
 * 智能体实例缓存的键，架构设计「引擎装配」规定为 {@code (agent_version_id, environment_id, environment_revision)}。
 *
 * <p>子智能体按父智能体版本加子智能体名单独装配和缓存（架构设计「子智能体」），所以多一个可空的子智能体名。
 *
 * @param agentVersionId 智能体版本 ID（{@code agv_…}）；必填
 * @param environmentId 环境 ID（{@code env_…}）；必填
 * @param environmentRevision 环境修订号，从 1 开始
 * @param subagentName 子智能体名；可为空，表示主智能体
 */
public record AssemblyKey(String agentVersionId, String environmentId, long environmentRevision, String subagentName) {

    /**
     * 创建缓存键。
     *
     * @throws NullPointerException 缺少智能体版本 ID 或环境 ID 时
     */
    public AssemblyKey {
        Objects.requireNonNull(agentVersionId, "agentVersionId");
        Objects.requireNonNull(environmentId, "environmentId");
    }

    /**
     * 是否为子智能体的键。
     *
     * @return 有子智能体名时为 true
     */
    public boolean isSubagent() {
        return subagentName != null;
    }

    /**
     * 生成稳定的标识，用作 AgentScope 的 {@code agentId}：主智能体为智能体版本 ID，子智能体再加上子智能体名。
     *
     * @return 标识，例如 {@code agv_01J…} 或 {@code agv_01J…/reader}
     */
    public String agentId() {
        return subagentName == null ? agentVersionId : agentVersionId + "/" + subagentName;
    }
}
