/**
 * 认证与请求上下文：API Key 校验、OIDC、服务间 JWT 与服务间 HTTP 调用约定，请求 ID 与 problem+json 错误映射，从请求填充 {@code PlatformContext}。
 *
 * <p>边界：不负责授权数据的存储与管理（租户、成员、角色在 control-plane），不访问数据库。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-api}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.security;
