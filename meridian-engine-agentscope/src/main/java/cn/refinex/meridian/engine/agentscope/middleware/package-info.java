/**
 * 平台中间件：经 AgentScope 的 {@code MiddlewareBase} 扩展执行行为，按架构设计「平台中间件」表实现；禁止 fork 或继承
 * AgentScope 的智能体类。
 *
 * <p>T04 提供 {@code PlatformContextMiddleware}、{@code PlatformTracingMiddleware}（装配 AgentScope 的
 * {@code OtelTracingMiddleware} 并补充平台属性）、{@code LeaseGuardMiddleware}、{@code ToolSpanMiddleware}，由
 * {@link cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider} 交给装配器。中间件的执行顺序由
 * {@code order()} 决定（数值大的在外层），各自的取值与测得的顺序见模块 {@code CLAUDE.md}（P13）。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4 及其带来的 OpenTelemetry API
 * 与 Reactor 上下文传播（{@code opentelemetry-reactor-3.1}，与 {@code OtelTracingMiddleware} 使用同一套）。
 */
package cn.refinex.meridian.engine.agentscope.middleware;
