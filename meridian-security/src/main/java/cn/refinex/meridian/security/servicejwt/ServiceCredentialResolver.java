package cn.refinex.meridian.security.servicejwt;

import java.util.Optional;

/**
 * 服务凭据的来源端口：按 {@code Authorization: Bearer} 中的凭据找到调用方服务。实现是 {@link ServiceJwtVerifier}（ADR-0044）。
 *
 * <p>实现可以阻塞，WebFlux 中经 {@code Blocking} 调用；必须线程安全。
 */
@FunctionalInterface
public interface ServiceCredentialResolver {

    /**
     * 解析凭据。
     *
     * @param credential 凭据原文，不为空
     * @return 调用方服务；凭据无效时为空
     */
    Optional<ServicePrincipal> resolve(String credential);
}
