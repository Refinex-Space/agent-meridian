/**
 * 平台与智能体内核之间的契约：内核适配层接口（{@code AgentEngine}、{@code SandboxDriver}、{@code ModelInvoker}）、由 runtime 实现的端口接口（{@code LeaseRegistry}、{@code InboxPort}、{@code ModelGateway}、{@code EventSinkPort} 等）与平台事件模型。
 *
 * <p>边界：不包含任何实现。签名里只出现平台类型，禁止出现 AgentScope 与 Spring 的类型，见架构设计「端口」。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.engine.spi;
