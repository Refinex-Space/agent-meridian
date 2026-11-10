/**
 * 认证相关的测试夹具（ADR-0044）：{@link cn.refinex.meridian.testsupport.auth.TestTokenIssuer} 签发服务 JWT 与任意声明的令牌，
 * {@link cn.refinex.meridian.testsupport.auth.KeycloakRealm} 是以单例复用的 Keycloak 容器与开发 realm（测试用户经管理 REST API 动态
 * 创建，令牌经直接授权取得）。
 */
package cn.refinex.meridian.testsupport.auth;
