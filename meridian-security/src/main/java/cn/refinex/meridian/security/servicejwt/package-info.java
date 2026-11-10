/**
 * 服务之间调用的认证（架构设计「认证」中"服务之间"一行，ADR-0044）：入站时 {@code /internal/} 下的请求要带 control-plane 签发的服务
 * JWT，由 {@link cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier} 校验（WebFlux 与 MVC 各一个过滤器，共用
 * {@link cn.refinex.meridian.security.servicejwt.ServiceCredentialAuthenticator}），处理器再按调用方服务名限定；出站时
 * {@link cn.refinex.meridian.security.servicejwt.ServiceTokenClient} 用客户端密钥换取并缓存令牌，经
 * {@link cn.refinex.meridian.security.servicejwt.ServiceRestClients} 构造带超时与令牌的 {@code RestClient}。
 *
 * <p>签发（密钥、令牌端点、JWKS）在 control-plane；本包只有校验与签发客户端。允许的依赖：{@code meridian-common}、
 * {@code meridian-api} 的内部 DTO、Spring Web、Nimbus JOSE + JWT。
 */
package cn.refinex.meridian.security.servicejwt;
