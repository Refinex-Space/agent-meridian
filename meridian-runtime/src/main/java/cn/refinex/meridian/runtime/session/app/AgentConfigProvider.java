package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;

/**
 * runtime 内部的智能体配置来源：已解析的智能体版本与环境（架构设计「引擎装配」）。Worker 用它取智能体版本的权限策略，
 * engine-spi 的 {@code AgentConfigPort} 也由它实现。
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
}
