/**
 * API Key 认证、作用域授权与限流（架构设计「安全架构」中的「认证」「授权」，「API 设计」中的「约定」，ADR-0042）：从
 * {@code Authorization: Bearer} 解析 API Key，经 {@link cn.refinex.meridian.security.apikey.ApiKeyResolver} 得到主体（租户、项目、
 * 作用域、每分钟上限），按 Key 限流，为请求填充 {@code PlatformContext}。
 *
 * <p>正式来源是 {@link cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver}（经 control-plane 内部接口按哈希校验，缓存 60 秒，
 * 吊销经 Pub/Sub 立即失效）；{@link cn.refinex.meridian.security.apikey.DevApiKeyResolver} 只在 {@code test} profile 生效。没有任何来源时
 * 一律拒绝，不会放行未认证的请求。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-api}（内部接口的 DTO）、Spring Web 与 WebFlux、Spring Data Redis（可选）、
 * Caffeine；不访问数据库。
 */
package cn.refinex.meridian.security.apikey;
