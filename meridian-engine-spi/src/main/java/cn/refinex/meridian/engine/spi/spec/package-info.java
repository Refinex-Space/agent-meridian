/**
 * 已解析的配置模型：智能体版本（{@link cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec}）、环境
 * （{@link cn.refinex.meridian.engine.spi.spec.EnvironmentSpec}）、平台权限规则模型与工具声明。
 *
 * <p>这些类型由 runtime 从 control-plane 下发的配置映射而来（T15b），内核据此装配智能体。它们是值对象：
 * 构造时校验取值范围并做防御性复制，之后不可变。字段的单位与取值范围写在各记录的 Javadoc 中。
 *
 * <p>边界：不含任何 AgentScope 类型，向 AgentScope 的翻译在 {@code meridian-engine-agentscope}。
 * 只依赖 JDK 与 {@code meridian-common}。
 */
package cn.refinex.meridian.engine.spi.spec;
