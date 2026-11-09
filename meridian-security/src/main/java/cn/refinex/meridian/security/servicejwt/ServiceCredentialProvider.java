package cn.refinex.meridian.security.servicejwt;

/**
 * 出站服务凭据的来源：本服务调用其他服务的内部接口时放进 {@code Authorization: Bearer} 的值。
 *
 * <p>T14a 只有开发期的静态凭据（{@code meridian.security.service-client.credential}，只在 {@code dev}、{@code test}）；T14b 换成
 * control-plane 签发的服务 JWT，实现负责缓存与过期前刷新（ADR-0042）。实现必须线程安全，不能记录凭据。
 */
@FunctionalInterface
public interface ServiceCredentialProvider {

    /**
     * 返回当前可用的服务凭据。
     *
     * @return 凭据明文
     */
    String credential();
}
