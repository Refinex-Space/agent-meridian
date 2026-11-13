package cn.refinex.meridian.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcTokenVerifier}（ADR-0043、ADR-0044）：只按标准 OIDC 校验（发现文档、JWKS、{@code iss}、{@code aud}、{@code exp}），用户标识
 * 与组按配置的声明取出。签发方是测试夹具而不是 Keycloak，证明不依赖某家 IdP 的行为；Keycloak 的端到端在 control-plane 与 runtime 的
 * 集成测试中。
 */
class OidcTokenVerifierTest {

    private final TestTokenIssuer idp = TestTokenIssuer.start();

    @AfterEach
    void stop() {
        idp.close();
    }

    private OidcProperties properties(String userIdClaim, String jwksUri) {
        return new OidcProperties(
                idp.url(), "meridian", jwksUri, userIdClaim, "groups", Duration.ofMinutes(5), 600, "email", true);
    }

    private JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(idp.url())
                .audience(List.of("meridian", "account"))
                .subject("5b0c7c1e-0f3a-4c55-9a8e-1d2f3a4b5c6d")
                .claim("preferred_username", "alice")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    @Test
    void theDefaultUserIdIsSubAndGroupsAreRead() {
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("sub", null));

        assertThat(verifier.resolve(idp.sign(claims().claim("groups", List.of("ops", "meridian-platform-admins"))
                        .build())))
                .contains(new ConsoleUser(
                        "5b0c7c1e-0f3a-4c55-9a8e-1d2f3a4b5c6d", Set.of("ops", "meridian-platform-admins")));
    }

    @Test
    void anotherClaimCanBeTheUserIdAndASingleStringGroupIsAccepted() {
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("preferred_username", idp.jwksUrl()));

        assertThat(verifier.resolve(idp.sign(claims().claim("groups", "ops").build())))
                .contains(new ConsoleUser("alice", Set.of("ops")));
        assertThat(verifier.resolve(idp.sign(claims().claim("groups", 42).build())))
                .contains(new ConsoleUser("alice", Set.of()));
    }

    @Test
    void onlyAVerifiedEmailIsTakenUnlessConfiguredOtherwise() {
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("preferred_username", null));

        assertThat(verifier.resolve(idp.sign(claims().claim("email", "alice@example.com")
                                .claim("email_verified", true)
                                .build()))
                        .orElseThrow()
                        .verifiedEmail())
                .isEqualTo("alice@example.com");
        assertThat(verifier.resolve(idp.sign(claims().claim("email", "alice@example.com")
                                .claim("email_verified", false)
                                .build()))
                        .orElseThrow()
                        .verifiedEmail())
                .as("未验证的邮箱不用于绑定")
                .isNull();
        assertThat(verifier.resolve(idp.sign(
                                claims().claim("email", "alice@example.com").build()))
                        .orElseThrow()
                        .verifiedEmail())
                .isNull();

        OidcTokenVerifier trusting = new OidcTokenVerifier(new OidcProperties(
                idp.url(), "meridian", null, "sub", "groups", Duration.ofMinutes(5), 600, "upn", false));
        assertThat(trusting.resolve(idp.sign(
                                claims().claim("upn", "bob@corp.example").build()))
                        .orElseThrow()
                        .verifiedEmail())
                .isEqualTo("bob@corp.example");
    }

    @Test
    void tokensFailingTheStandardChecksAreRejected() {
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("sub", null));
        Instant past = Instant.now().minus(Duration.ofHours(1));

        assertThat(verifier.resolve(idp.sign(claims().audience("account").build())))
                .isEmpty();
        assertThat(verifier.resolve(
                        idp.sign(claims().issuer(idp.url() + "/other").build())))
                .isEmpty();
        assertThat(verifier.resolve(
                        idp.sign(claims().expirationTime(Date.from(past)).build())))
                .isEmpty();
        assertThat(verifier.resolve(idp.signWithForeignKey(claims().build()))).isEmpty();
        assertThat(verifier.resolve("mk_live_notajwt")).isEmpty();
    }

    @Test
    void aMissingUserIdClaimIsRejected() {
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("oid", null));

        assertThat(verifier.resolve(idp.sign(claims().build()))).isEmpty();
        assertThat(verifier.resolve(idp.sign(claims().claim("oid", "u-1").build())))
                .contains(new ConsoleUser("u-1"));
    }

    @Test
    void anUnreachableIdentityProviderIsAnOutage() {
        String token = idp.sign(claims().build());
        OidcTokenVerifier verifier = new OidcTokenVerifier(properties("sub", null));
        idp.close();

        assertThatThrownBy(() -> verifier.resolve(token))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
