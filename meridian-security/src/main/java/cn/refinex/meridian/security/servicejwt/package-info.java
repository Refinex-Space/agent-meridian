/**
 * 服务之间调用的认证（架构设计「认证」中"服务之间"一行）：{@code /internal/} 下的请求要带服务凭据。
 *
 * <p>正式实现是 control-plane 签发的服务 JWT（T14b），经 {@link cn.refinex.meridian.security.servicejwt.ServiceCredentialResolver}
 * 端口接入；T09b 先提供只在 {@code dev}、{@code test} profile 生效的静态服务凭据。
 */
package cn.refinex.meridian.security.servicejwt;
