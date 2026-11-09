/**
 * 服务之间调用的认证（架构设计「认证」中"服务之间"一行）：入站时 {@code /internal/} 下的请求要带服务凭据（WebFlux 与 MVC 各一个过滤器，
 * 共用 {@link cn.refinex.meridian.security.servicejwt.ServiceCredentialAuthenticator}）；出站时经
 * {@link cn.refinex.meridian.security.servicejwt.ServiceRestClients} 构造带超时与凭据的 {@code RestClient}。
 *
 * <p>正式实现是 control-plane 签发的服务 JWT（T14b），经 {@link cn.refinex.meridian.security.servicejwt.ServiceCredentialResolver}
 * 与 {@link cn.refinex.meridian.security.servicejwt.ServiceCredentialProvider} 两个端口接入；T09b、T14a 先提供只在 {@code dev}、
 * {@code test} profile 生效的静态服务凭据。
 */
package cn.refinex.meridian.security.servicejwt;
