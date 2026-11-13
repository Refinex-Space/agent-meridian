package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.BearerTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验 OIDC access token 的 {@link ConsoleUserResolver}（ADR-0043、ADR-0044）：签名（JWKS 中 RSA、EC 密钥对应的算法）、{@code iss}
 * 逐字相等、{@code aud} 包含配置的受众、{@code exp} 未过（容许 30 秒偏差）；用户标识与组按配置的声明取出。
 *
 * <p>JWKS 地址在第一次校验时确定（配置给出，或读取签发方的发现文档），IdP 晚于本服务启动也能工作；读取失败时抛 503 并在下次重试。
 * 公钥缓存与未知 {@code kid} 的刷新由 Nimbus 的 {@link JWKSourceBuilder} 完成。线程安全；不记录令牌。
 */
public final class OidcTokenVerifier implements ConsoleUserResolver {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenVerifier.class);

    /** 发现文档相对签发方的路径（OpenID Connect Discovery 1.0 第 4 节）。 */
    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /** 接受的签名算法：JWKS 中 RSA 与 EC 密钥能验证的全部算法，不接受 HMAC 与 {@code none}。 */
    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(
            JWSAlgorithm.RS256,
            JWSAlgorithm.RS384,
            JWSAlgorithm.RS512,
            JWSAlgorithm.PS256,
            JWSAlgorithm.PS384,
            JWSAlgorithm.PS512,
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512);

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    static final int CONNECT_TIMEOUT_MS = 2_000;

    static final int READ_TIMEOUT_MS = 5_000;

    static final int SIZE_LIMIT = 256 * 1024;

    static final long REFRESH_TIMEOUT_MS = 15_000;

    private final OidcProperties properties;
    private final DefaultResourceRetriever retriever =
            new DefaultResourceRetriever(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, SIZE_LIMIT);
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    /**
     * 创建校验器。
     *
     * @param properties OIDC 配置；签发方与受众必填
     * @throws IllegalStateException 缺少签发方或受众时
     */
    public OidcTokenVerifier(OidcProperties properties) {
        if (!properties.enabled()
                || properties.audience() == null
                || properties.audience().isBlank()) {
            throw new IllegalStateException("OIDC 需要 meridian.security.oidc.issuer 与 audience");
        }
        this.properties = properties;
    }

    @Override
    public Optional<ConsoleUser> resolve(String token) {
        if (!BearerTokens.looksLikeJwt(token)) {
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = processor().process(token, null);
        } catch (KeySourceException e) {
            throw unavailable(e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.debug("OIDC 令牌无效：{}", e.getMessage());
            return Optional.empty();
        }
        Object subject = claims.getClaim(properties.userIdClaim());
        if (!(subject instanceof String id) || id.isBlank()) {
            log.debug("OIDC 令牌缺少用户标识声明 {}", properties.userIdClaim());
            return Optional.empty();
        }
        return Optional.of(new ConsoleUser(id, groups(claims.getClaim(properties.groupsClaim())), email(claims)));
    }

    /** 经过验证的邮箱：按配置要求 {@code email_verified} 为真，声明不是字符串时视为没有。 */
    private String email(JWTClaimsSet claims) {
        if (!(claims.getClaim(properties.emailClaim()) instanceof String email) || email.isBlank()) {
            return null;
        }
        if (properties.requireVerifiedEmail() && !Boolean.TRUE.equals(claims.getClaim("email_verified"))) {
            return null;
        }
        return email;
    }

    /** 组声明容忍字符串与字符串数组两种形状（各家 IdP 不同，ADR-0043），其他形状视为没有组。 */
    private static Set<String> groups(Object claim) {
        if (claim instanceof String single) {
            return Set.of(single);
        }
        if (claim instanceof List<?> list) {
            Set<String> groups = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String name) {
                    groups.add(name);
                }
            }
            return groups;
        }
        return Set.of();
    }

    private DefaultJWTProcessor<SecurityContext> processor() {
        DefaultJWTProcessor<SecurityContext> current = processor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (processor == null) {
                processor = build(jwksUri());
            }
            return processor;
        }
    }

    private DefaultJWTProcessor<SecurityContext> build(URL jwksUri) {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(jwksUri, retriever)
                .cache(properties.jwksCacheTtl().toMillis(), REFRESH_TIMEOUT_MS)
                .retrying(false)
                .build();
        DefaultJWTProcessor<SecurityContext> jwt = new DefaultJWTProcessor<>();
        jwt.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
        // access token 的 typ：大多数 IdP 写 JWT 或不写，RFC 9068 规定 at+jwt
        jwt.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                properties.audience(),
                new JWTClaimsSet.Builder().issuer(properties.issuer()).build(),
                Set.of("exp", properties.userIdClaim()));
        claims.setMaxClockSkew((int) CLOCK_SKEW.toSeconds());
        jwt.setJWTClaimsSetVerifier(claims);
        return jwt;
    }

    private URL jwksUri() {
        try {
            if (properties.jwksUri() != null && !properties.jwksUri().isBlank()) {
                return URI.create(properties.jwksUri()).toURL();
            }
            String issuer = properties.issuer().endsWith("/")
                    ? properties.issuer().substring(0, properties.issuer().length() - 1)
                    : properties.issuer();
            Resource discovery = retriever.retrieveResource(
                    URI.create(issuer + DISCOVERY_PATH).toURL());
            Map<String, Object> document = JSONObjectUtils.parse(discovery.getContent());
            String jwks = JSONObjectUtils.getString(document, "jwks_uri");
            if (jwks == null) {
                throw new IOException("发现文档没有 jwks_uri");
            }
            return URI.create(jwks).toURL();
        } catch (IOException | ParseException | IllegalArgumentException e) {
            throw unavailable(e);
        }
    }

    private static MeridianException unavailable(Exception cause) {
        return new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "身份提供方暂时无法访问，请稍后重试", cause);
    }
}
