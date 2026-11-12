package cn.refinex.meridian.controlplane.servicetoken.app;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.servicetoken.domain.ServiceClient;
import cn.refinex.meridian.controlplane.servicetoken.domain.ServiceTokenErrorCode;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 签发服务 JWT 的用例（架构设计「认证」中"服务之间"一行，ADR-0044）：认证服务客户端、检查目标服务、用当前签名密钥签发 RS256 令牌，
 * 有效 {@link ServiceJwts#TOKEN_TTL}。不访问数据库；客户端来自配置。线程安全。
 */
@Service
public class ServiceTokenIssuer {

    /** 未登记的服务名用它比较摘要；它的密钥是随机的，不会被匹配。 */
    private static final ServiceClient DECOY =
            ServiceClient.of("decoy", UUID.randomUUID().toString(), List.of(ServiceJwts.CONTROL_PLANE));

    private final Map<String, ServiceClient> clients;
    private final SigningKeySource keys;
    private final String issuer;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param properties control-plane 配置，取签发方与客户端
     * @param keys 签名密钥
     * @param clock 时钟
     * @throws IllegalStateException 客户端配置不完整或重复时
     */
    public ServiceTokenIssuer(ControlPlaneProperties properties, SigningKeySource keys, Clock clock) {
        Map<String, ServiceClient> byName = new HashMap<>();
        for (ControlPlaneProperties.Client client : properties.serviceTokens().clients()) {
            ServiceClient parsed = ServiceClient.of(client.service(), client.secret(), client.audiences());
            if (byName.put(parsed.service(), parsed) != null) {
                throw new IllegalStateException("服务客户端重复：" + parsed.service());
            }
        }
        this.clients = Map.copyOf(byName);
        this.keys = keys;
        this.issuer = properties.serviceTokens().issuer();
        this.clock = clock;
    }

    /**
     * 签发令牌。
     *
     * @param service 客户端的服务名；可为空，表示请求没有 Basic 认证
     * @param secret 客户端密钥；可为空
     * @param grantType 授权类型，只支持 {@value ServiceJwts#GRANT_TYPE}
     * @param audience 目标服务名
     * @return 令牌与有效期
     * @throws MeridianException 客户端认证失败（401 {@code auth.invalid_service_client}）、授权类型不支持（400）时
     * @throws ForbiddenException 目标服务不在该客户端的允许列表中时（{@code access.forbidden}）
     */
    public IssuedToken issue(String service, String secret, String grantType, String audience) {
        ServiceClient client = service == null ? null : clients.get(service);
        // 未登记的服务名也比较一次摘要，响应时间不泄露服务名是否登记
        boolean matches = (client != null ? client : DECOY).matches(secret == null ? "" : secret);
        if (client == null || !matches) {
            throw new MeridianException(ServiceTokenErrorCode.INVALID_SERVICE_CLIENT, "服务名与客户端密钥不匹配");
        }
        if (!ServiceJwts.GRANT_TYPE.equals(grantType)) {
            throw new MeridianException(CommonErrorCode.REQUEST_INVALID, "grant_type 只支持 " + ServiceJwts.GRANT_TYPE);
        }
        if (!client.mayCall(audience)) {
            throw new ForbiddenException(CommonErrorCode.FORBIDDEN, "服务 " + service + " 不能换取 " + audience + " 的令牌");
        }
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(service)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ServiceJwts.TOKEN_TTL)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        return new IssuedToken(sign(claims), ServiceJwts.TOKEN_TTL);
    }

    /**
     * 为 control-plane 自己签发调用其他服务的令牌（{@code sub} 为 {@value ServiceJwts#CONTROL_PLANE}，ADR-0051）：control-plane 是签发方，
     * 不经令牌端点、不需要客户端密钥。
     *
     * @param audience 目标服务名，例如 {@value ServiceJwts#KNOWLEDGE}
     * @return 令牌与有效期
     */
    public IssuedToken issueOwn(String audience) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(ServiceJwts.CONTROL_PLANE)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ServiceJwts.TOKEN_TTL)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        return new IssuedToken(sign(claims), ServiceJwts.TOKEN_TTL);
    }

    private String sign(JWTClaimsSet claims) {
        RSAKey key = keys.current();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("服务 JWT 签名失败", e);
        }
    }

    /**
     * 签发的令牌。
     *
     * @param token JWT
     * @param ttl 有效期
     */
    public record IssuedToken(String token, Duration ttl) {}
}
