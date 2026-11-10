package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.web.BearerTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验服务 JWT 的 {@link ServiceCredentialResolver}（ADR-0044）：RS256 签名、{@code iss} 等于签发方、{@code aud} 包含本服务名、
 * {@code exp} 未过（容许 {@link ServiceJwts#CLOCK_SKEW}），{@code sub} 是调用方服务名。
 *
 * <p>公钥来自 {@link JWKSource}：其他服务经 HTTP 读取 control-plane 的 JWKS（{@link #remote}：缓存，遇到未知 {@code kid} 立即刷新一次，
 * 刷新限频），control-plane 自己用进程内的密钥集。JWKS 读不到时抛 503，不把依赖故障说成凭据无效。线程安全：处理器无状态。
 */
public final class ServiceJwtVerifier implements ServiceCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtVerifier.class);

    /** JWKS 请求的连接与读取超时：control-plane 在同一集群内，与服务间调用的默认值相同。 */
    static final int JWKS_CONNECT_TIMEOUT_MS = 2_000;

    static final int JWKS_READ_TIMEOUT_MS = 5_000;

    /** JWKS 响应的大小上限。 */
    static final int JWKS_SIZE_LIMIT = 64 * 1024;

    /** 缓存刷新时等待其他线程完成的上限。 */
    static final long JWKS_REFRESH_TIMEOUT_MS = 15_000;

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    /**
     * 创建校验器。
     *
     * @param keys 公钥来源
     * @param issuer 签发方（{@code iss}）
     * @param audience 本服务名：令牌的 {@code aud} 必须包含它
     */
    public ServiceJwtVerifier(JWKSource<SecurityContext> keys, String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> jwt = new DefaultJWTProcessor<>();
        jwt.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                audience, new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp", "iat"));
        claims.setMaxClockSkew((int) ServiceJwts.CLOCK_SKEW.toSeconds());
        jwt.setJWTClaimsSetVerifier(claims);
        this.processor = jwt;
    }

    /**
     * 经 HTTP 读取 JWKS 的校验器。
     *
     * @param jwksUrl control-plane 的 JWKS 地址
     * @param issuer 签发方
     * @param audience 本服务名
     * @param cacheTtl JWKS 的缓存时长
     * @return 校验器
     */
    public static ServiceJwtVerifier remote(URL jwksUrl, String issuer, String audience, Duration cacheTtl) {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(
                        jwksUrl,
                        new DefaultResourceRetriever(JWKS_CONNECT_TIMEOUT_MS, JWKS_READ_TIMEOUT_MS, JWKS_SIZE_LIMIT))
                .cache(cacheTtl.toMillis(), JWKS_REFRESH_TIMEOUT_MS)
                .retrying(false)
                .build();
        return new ServiceJwtVerifier(keys, issuer, audience);
    }

    @Override
    public Optional<ServicePrincipal> resolve(String credential) {
        if (!BearerTokens.looksLikeJwt(credential)) {
            return Optional.empty();
        }
        try {
            JWTClaimsSet claims = processor.process(credential, null);
            return Optional.of(new ServicePrincipal(claims.getSubject()));
        } catch (KeySourceException e) {
            throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "服务 JWT 的公钥暂时无法获取，请稍后重试", e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.debug("服务 JWT 无效：{}", e.getMessage());
            return Optional.empty();
        }
    }
}
