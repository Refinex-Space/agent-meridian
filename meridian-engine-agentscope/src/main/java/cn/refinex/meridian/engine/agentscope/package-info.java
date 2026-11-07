/**
 * 内核适配层的 AgentScope 实现，是唯一依赖 AgentScope 的生产模块：智能体装配、{@code RoutedModel}、带租约校验的状态存储、文件系统、平台工具、平台中间件、事件映射与沙箱驱动。
 *
 * <p>边界：不掌控会话语义：会话、事件、收件箱、待处理动作、租约与崩溃恢复都属于 runtime，本模块只执行一轮；需要的 runtime 能力一律经 {@code meridian-engine-spi} 的端口获取。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-engine-spi} 与 AgentScope（core、harness、Kubernetes 沙箱扩展，版本 2.0.4）。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.engine.agentscope;
