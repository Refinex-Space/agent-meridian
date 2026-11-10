package cn.refinex.meridian.security.servicejwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 服务 JWT 的配置（{@code meridian.security.service-jwt}，ADR-0044）。
 *
 * @param service 本服务名：入站令牌的 {@code aud} 必须是它，出站换取令牌时它是客户端 ID；为空时本服务不接受任何服务 JWT
 * @param issuer 签发方（{@code iss}），默认 {@value ServiceJwts#DEFAULT_ISSUER}
 * @param clientSecret 本服务的客户端密钥，只用于向 control-plane 换取令牌；为空时本服务不能调用其他服务的内部接口。只能来自环境
 *     变量或 Secret
 * @param jwksUrl 公钥地址；为空时取 control-plane 地址加 {@value ServiceJwts#JWKS_PATH}
 * @param jwksCacheTtl 公钥的缓存时长，默认 5 分钟；遇到未知 {@code kid} 时不等它过期，立即刷新一次
 */
@ConfigurationProperties("meridian.security.service-jwt")
public record ServiceJwtProperties(
        String service,
        @DefaultValue(ServiceJwts.DEFAULT_ISSUER) String issuer,
        String clientSecret,
        String jwksUrl,
        @DefaultValue("5m") Duration jwksCacheTtl) {

    /**
     * 是否配置了本服务名。
     *
     * @return 配置了非空服务名时为 true
     */
    public boolean hasService() {
        return service != null && !service.isBlank();
    }

    /**
     * 是否配置了客户端密钥。
     *
     * @return 配置了非空密钥时为 true
     */
    public boolean hasClientSecret() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * 不打印客户端密钥。
     *
     * @return 不含密钥的描述
     */
    @Override
    public String toString() {
        return "ServiceJwtProperties[service=" + service + ", issuer=" + issuer + ", jwksUrl=" + jwksUrl + "]";
    }
}
