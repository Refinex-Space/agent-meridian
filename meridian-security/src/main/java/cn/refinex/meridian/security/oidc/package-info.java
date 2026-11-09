/**
 * 控制台用户的认证（架构设计「认证」中"控制台用户"一行）：{@code /admin/} 下的请求带控制台用户令牌，认证后得到主体标识
 * {@link cn.refinex.meridian.security.oidc.ConsoleUser}。
 *
 * <p>T14a 只有开发期令牌（{@code dev}、{@code test}）；T14b 实现 {@link cn.refinex.meridian.security.oidc.ConsoleUserResolver} 校验 OIDC
 * access token，并让公开 API 配合 {@code Meridian-Project} 头接受它。角色不在本包：由 {@code rbac} 包按角色绑定授权。
 *
 * <p>允许的依赖：{@code meridian-common}、Spring Web MVC；不访问数据库。
 */
package cn.refinex.meridian.security.oidc;
