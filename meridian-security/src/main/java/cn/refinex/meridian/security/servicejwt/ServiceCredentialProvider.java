package cn.refinex.meridian.security.servicejwt;

/**
 * 出站服务凭据的来源：本服务调用其他服务的内部接口时放进 {@code Authorization: Bearer} 的值。实现是 {@link ServiceTokenClient}：
 * control-plane 签发的服务 JWT，按目标服务缓存与过期前刷新（ADR-0044）。实现必须线程安全，不能记录凭据。
 */
@FunctionalInterface
public interface ServiceCredentialProvider {

    /**
     * 返回调用某个服务时可用的凭据。
     *
     * @param audience 目标服务名，例如 {@code control-plane}
     * @return 凭据明文
     */
    String credential(String audience);
}
