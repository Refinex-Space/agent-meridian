/**
 * 控制台用户的认证（架构设计「认证」中"控制台用户"一行，ADR-0043、ADR-0044）：{@link cn.refinex.meridian.security.oidc.OidcTokenVerifier}
 * 按标准 OIDC 校验 access token，按配置的声明取出用户标识与组，得到 {@link cn.refinex.meridian.security.oidc.ConsoleUser}。
 *
 * <p>MVC 服务的 {@code /admin/} 由 {@link cn.refinex.meridian.security.oidc.ConsoleUserFilter} 认证；WebFlux 服务的公开 API 上，
 * {@link cn.refinex.meridian.security.oidc.ConsoleApiAuthenticator} 按 {@code Meridian-Project} 经 control-plane 查询该用户在项目上的
 * 作用域。角色不在本包：由 {@code rbac} 包按角色绑定授权。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code apikey} 包（公开 API 的主体与作用域）、{@code servicejwt} 包（调用 control-plane）、
 * Spring Web、Nimbus JOSE + JWT；不访问数据库，不依赖任何 IdP 的专有接口。
 */
package cn.refinex.meridian.security.oidc;
