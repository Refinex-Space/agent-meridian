package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;

/**
 * runtime 内部的智能体配置来源：已解析的智能体版本与环境（架构设计「引擎装配」）。Worker 用它取智能体版本的权限策略，
 * engine-spi 的 {@code AgentConfigPort} 也由它实现；创建会话时用它解析并固化智能体版本与环境修订号（T07）。
 *
 * <p>找不到时抛 {@link cn.refinex.meridian.common.error.NotFoundException}，错误码为 {@code agent.not_found} 或
 * {@code environment.not_found}（{@code session.domain.SessionErrorCode}）。T15b 起按租户与项目隔离：一个项目只能使用自己的智能体与环境。
 *
 * <p>T06 的实现从开发配置目录读取 YAML（{@code config.FileAgentConfigProvider}）；T15b 起改为从 control-plane 下发的配置读取，
 * 文件实现只留给开发与测试。方法会阻塞，响应式调用方经 {@code Blocking} 调用。实现必须线程安全。
 */
public interface AgentConfigProvider {

    /**
     * 读取智能体版本配置。
     *
     * @param agentVersionId 智能体版本 ID（{@code agv_…}）
     * @return 已解析的配置
     * @throws cn.refinex.meridian.common.error.NotFoundException 不存在时
     */
    ResolvedAgentSpec agentVersion(String agentVersionId);

    /**
     * 读取环境在指定修订号下的配置。
     *
     * @param environmentId 环境 ID（{@code env_…}）
     * @param revision 修订号，从 1 开始
     * @return 已解析的配置
     * @throws cn.refinex.meridian.common.error.NotFoundException 不存在时
     */
    EnvironmentSpec environment(String environmentId, long revision);

    /**
     * 读取智能体当前发布的版本，创建会话时按智能体 ID 绑定版本用。
     *
     * @param agentId 智能体 ID（{@code agt_…}）
     * @return 当前版本的已解析配置
     * @throws cn.refinex.meridian.common.error.NotFoundException 智能体不存在或没有发布的版本时
     */
    ResolvedAgentSpec currentAgentVersion(String agentId);

    /**
     * 读取环境当前的修订，创建会话时固化修订号用。
     *
     * @param environmentId 环境 ID（{@code env_…}）
     * @return 当前修订的配置
     * @throws cn.refinex.meridian.common.error.NotFoundException 环境不存在时
     */
    EnvironmentSpec currentEnvironment(String environmentId);

    /**
     * 读取一个技能版本的内容，内核装配时生成受保护路径 {@code skills/} 与技能清单用（T10a）。
     *
     * @param skillId 技能 ID（{@code skl_…}）
     * @param version 技能版本号
     * @return 技能内容
     * @throws cn.refinex.meridian.common.error.NotFoundException 不存在时
     */
    SkillDefinition skill(String skillId, int version);

    /**
     * 读取租户级设置：租户提示词与联网搜索后端（T10b，ADR-0035）。
     *
     * @param tenantId 租户 ID（{@code ten_…}）
     * @return 租户设置；租户没有配置时为 {@link TenantSettings#empty}
     */
    TenantSettings tenantSettings(String tenantId);
}
