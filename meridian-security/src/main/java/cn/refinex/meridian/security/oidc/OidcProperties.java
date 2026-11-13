package cn.refinex.meridian.security.oidc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 控制台用户的 OIDC 配置（{@code meridian.security.oidc}，ADR-0043、ADR-0044）。平台只认标准 OIDC：签发方、受众与声明映射都是
 * 配置，代码中不出现任何 IdP 的专有接口。
 *
 * @param issuer 签发方，与令牌的 {@code iss} 逐字相等，例如 {@code https://idp.example.com/realms/meridian}；为空时不接受任何
 *     控制台令牌
 * @param audience 受众：令牌的 {@code aud} 必须包含它
 * @param jwksUri 公钥地址；为空时从 {@code issuer} 的发现文档（{@code /.well-known/openid-configuration}）读取。服务访问 IdP
 *     的地址与 {@code iss} 不同时（容器内）设置它
 * @param userIdClaim 用户标识取哪个声明，默认 {@code sub}；必须稳定不变（例如 Entra ID 的 {@code oid}）
 * @param groupsClaim 组取哪个声明，默认 {@code groups}；取值可以是字符串或字符串数组，取不到时为空（按组判定平台管理员）
 * @param jwksCacheTtl 公钥的缓存时长，默认 5 分钟；遇到未知 {@code kid} 时立即刷新一次
 * @param rateLimitPerMinute 控制台用户调用公开 API 的每分钟上限（按用户计），默认 600，与 API Key 的默认值相同
 * @param emailClaim 邮箱取哪个声明，默认 {@code email}；只用于首次登录时绑定按邮箱邀请的成员（ADR-0053）
 * @param requireVerifiedEmail 是否只接受 {@code email_verified} 为 {@code true} 的邮箱，默认是。IdP 不发这个声明而邮箱由企业目录
 *     保证时（例如 Entra ID 的工作账号）才关闭：未验证的邮箱可被用户自己改成别人的，会绑定到别人的邀请
 */
@ConfigurationProperties("meridian.security.oidc")
public record OidcProperties(
        String issuer,
        String audience,
        String jwksUri,
        @DefaultValue("sub") String userIdClaim,
        @DefaultValue("groups") String groupsClaim,
        @DefaultValue("5m") Duration jwksCacheTtl,
        @DefaultValue("600") int rateLimitPerMinute,
        @DefaultValue("email") String emailClaim,
        @DefaultValue("true") boolean requireVerifiedEmail) {

    /**
     * 是否配置了签发方。
     *
     * @return 配置了非空签发方时为 true
     */
    public boolean enabled() {
        return issuer != null && !issuer.isBlank();
    }
}
