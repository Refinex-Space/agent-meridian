/**
 * Web 入口的公共能力：请求 ID（{@code X-Request-Id}，写入 MDC 与响应头）与 problem+json 错误映射，
 * 见架构设计「API 设计」中的「约定」与 ADR-0020。
 *
 * <p>映射逻辑只有 {@link cn.refinex.meridian.security.web.ProblemMapper} 一份；MVC 适配器
 * {@link cn.refinex.meridian.security.web.MvcProblemHandler} 与 WebFlux 适配器
 * {@link cn.refinex.meridian.security.web.WebFluxProblemHandler} 只负责传输。两种 Web 栈的依赖都是可选的，
 * 由 {@link cn.refinex.meridian.security.web.SecurityWebAutoConfiguration} 按应用类型装配。
 *
 * <p>认证与 {@code PlatformContext} 的填充不在本包（T07、T14a、T14b）。
 */
package cn.refinex.meridian.security.web;
