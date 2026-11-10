package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ServiceJwtVerifier}（T14b-A02 的单元部分）：签名、签发方、受众与有效期都要符合；公钥经 HTTP JWKS 读取，签名密钥轮换后遇到
 * 未知 {@code kid} 刷新一次即可继续校验；JWKS 读不到时是 503 而不是"凭据无效"。
 */
class ServiceJwtVerifierTest {

    private final TestTokenIssuer issuer = TestTokenIssuer.start();
    private final ServiceJwtVerifier verifier = verifier(issuer, "runtime");

    @AfterEach
    void stop() {
        issuer.close();
    }

    /**
     * 经 HTTP 读取测试签发方 JWKS 的校验器。
     *
     * @param issuer 测试签发方
     * @param audience 本服务名
     * @return 校验器
     */
    static ServiceJwtVerifier verifier(TestTokenIssuer issuer, String audience) {
        try {
            return ServiceJwtVerifier.remote(
                    URI.create(issuer.jwksUrl()).toURL(), ServiceJwts.DEFAULT_ISSUER, audience, Duration.ofMinutes(5));
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aValidTokenYieldsTheCallingService() {
        assertThat(verifier.resolve(issuer.serviceToken("knowledge", "runtime")))
                .contains(new ServicePrincipal("knowledge"));
    }

    @Test
    void expiredTokensAreRejectedBeyondTheClockSkew() {
        Instant now = Instant.now();
        String expired = issuer.sign(
                TestTokenIssuer.serviceClaims("knowledge", "runtime", now.minusSeconds(400), now.minusSeconds(40))
                        .build());
        String withinSkew = issuer.sign(
                TestTokenIssuer.serviceClaims("knowledge", "runtime", now.minusSeconds(300), now.minusSeconds(10))
                        .build());

        assertThat(verifier.resolve(expired)).isEmpty();
        assertThat(verifier.resolve(withinSkew)).isPresent();
    }

    @Test
    void tokensForAnotherServiceAreRejected() {
        assertThat(verifier.resolve(issuer.serviceToken("knowledge", "control-plane")))
                .isEmpty();
    }

    @Test
    void tokensFromAnotherIssuerOrKeyAreRejected() {
        Instant now = Instant.now();
        String otherIssuer =
                issuer.sign(TestTokenIssuer.serviceClaims("knowledge", "runtime", now, now.plusSeconds(300))
                        .issuer("someone-else")
                        .build());
        String forged = issuer.signWithForeignKey(
                TestTokenIssuer.serviceClaims("knowledge", "runtime", now, now.plusSeconds(300))
                        .build());

        assertThat(verifier.resolve(otherIssuer)).isEmpty();
        assertThat(verifier.resolve(forged)).isEmpty();
        assertThat(verifier.resolve("svc_dev_static_credential")).isEmpty();
    }

    @Test
    void afterKeyRotationAnUnknownKidRefreshesTheJwksOnce() {
        assertThat(verifier.resolve(issuer.serviceToken("knowledge", "runtime")))
                .isPresent();
        int fetches = issuer.jwksRequests();

        issuer.rotate();

        assertThat(verifier.resolve(issuer.serviceToken("knowledge", "runtime")))
                .isPresent();
        assertThat(issuer.jwksRequests()).isEqualTo(fetches + 1);
        // 新公钥已在缓存中，之后不再请求
        assertThat(verifier.resolve(issuer.serviceToken("knowledge", "runtime")))
                .isPresent();
        assertThat(issuer.jwksRequests()).isEqualTo(fetches + 1);
    }

    @Test
    void anUnreachableJwksIsAnOutageNotAnInvalidCredential() {
        String token = issuer.serviceToken("knowledge", "runtime");
        issuer.close();

        assertThatThrownBy(() -> verifier.resolve(token))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
