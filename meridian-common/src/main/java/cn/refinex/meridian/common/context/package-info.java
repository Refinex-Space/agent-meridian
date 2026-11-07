/**
 * 平台上下文 {@link cn.refinex.meridian.common.context.PlatformContext} 及其传递：MVC 侧基于
 * {@code ScopedValue}，WebFlux 侧基于 Reactor Context，见架构设计「身份映射」与 ADR-0020。
 *
 * <p>只负责承载与传递；从 HTTP 请求填充上下文在 {@code meridian-security}，进入轮次时补充线程与轮次在 runtime。
 * 本包可以依赖 reactor-core（架构设计「模块依赖规则」）。
 */
package cn.refinex.meridian.common.context;
