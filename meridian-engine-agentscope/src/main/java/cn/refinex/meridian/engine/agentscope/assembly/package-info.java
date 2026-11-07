/**
 * 智能体装配：把已解析的智能体版本与环境（{@code ResolvedAgentSpec} 加 {@code EnvironmentSpec}）按架构设计
 * 「引擎装配」表装配为 AgentScope {@code HarnessAgent}，并按 {@code (agent_version_id, environment_id,
 * environment_revision)} 缓存实例。
 *
 * <p>{@link cn.refinex.meridian.engine.agentscope.assembly.AgentAssembler} 只负责配置 {@code HarnessAgent.Builder}；
 * 尚未实现的平台组件（{@code RoutedModel}、平台中间件、平台工具、{@code FencedAgentStateStore}、文件系统、
 * 技能仓库）经构造参数注入的工厂或提供者获得。{@link cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache}
 * 管理实例的引用计数与淘汰。执行一轮的逻辑不在这里（T04）。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4、Caffeine。
 */
package cn.refinex.meridian.engine.agentscope.assembly;
