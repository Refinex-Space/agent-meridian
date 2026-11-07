/**
 * API Key 认证与作用域授权（架构设计「安全架构」中的「认证」「授权」）：从 {@code Authorization: Bearer} 解析 API Key，
 * 经 {@link cn.refinex.meridian.security.apikey.ApiKeyResolver} 得到主体（租户、项目、作用域），为请求填充 {@code PlatformContext}。
 *
 * <p>T07 只有开发期的 {@link cn.refinex.meridian.security.apikey.DevApiKeyResolver}（只在 dev、test profile 生效）；
 * 按哈希查找、缓存与吊销的实现由 T14a 提供。没有任何来源时一律拒绝，不会放行未认证的请求。
 *
 * <p>允许的依赖：{@code meridian-common}、Spring Web 与 WebFlux；不访问数据库。
 */
package cn.refinex.meridian.security.apikey;
