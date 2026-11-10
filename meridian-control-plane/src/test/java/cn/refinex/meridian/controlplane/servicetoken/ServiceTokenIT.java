package cn.refinex.meridian.controlplane.servicetoken;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.api.internal.model.ApiKeyVerification;
import cn.refinex.meridian.api.internal.model.ApiKeyVerifyRequest;
import cn.refinex.meridian.controlplane.servicetoken.app.SigningKeySource;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServicePrincipal;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import cn.refinex.meridian.security.servicejwt.ServiceTokenClient;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/**
 * 服务 JWT（T14b-A02）：runtime 用 {@code meridian-security} 的签发客户端换取令牌并调用内部接口；过期、受众不符、调用方不允许的令牌被
 * 拒绝；签名密钥轮换后，经 HTTP 读取 JWKS 的校验端遇到新的 {@code kid} 刷新一次仍然校验成功。
 */
class ServiceTokenIT extends ControlPlaneTestBase {

    @Autowired
    private SigningKeySource keys;

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private ServiceJwtVerifier remoteVerifier(String audience) throws Exception {
        return ServiceJwtVerifier.remote(
                URI.create(url() + ServiceJwts.JWKS_PATH).toURL(),
                ServiceJwts.DEFAULT_ISSUER,
                audience,
                Duration.ofMinutes(5));
    }

    private String sign(String subject, String audience, Instant expiresAt) throws JOSEException {
        RSAKey key = keys.current();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(ServiceJwts.DEFAULT_ISSUER)
                        .subject(subject)
                        .audience(audience)
                        .issueTime(Date.from(expiresAt.minusSeconds(300)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void runtimeCallsInternalEndpointsWithAServiceJwtFromTheTokenClient() {
        TenantFixture fixture = TenantFixture.create(client());
        ServiceClientProperties properties =
                new ServiceClientProperties(url(), Duration.ofSeconds(2), Duration.ofSeconds(5));
        ServiceTokenClient tokens = new ServiceTokenClient(
                RestClient.builder().baseUrl(url()).build(), RUNTIME_CLIENT, RUNTIME_SECRET, Clock.systemUTC());
        RestClient controlPlane = new ServiceRestClients(properties, tokens).create(url(), ServiceJwts.CONTROL_PLANE);

        ApiKeyVerification verified = controlPlane
                .post()
                .uri("/internal/v1/api-keys:verify")
                .body(new ApiKeyVerifyRequest(ApiKeyHashes.sha256(fixture.get("key_plaintext"))))
                .retrieve()
                .body(ApiKeyVerification.class);

        assertThat(verified.getApiKeyId()).isEqualTo(fixture.get("key"));
        String token = tokens.credential(ServiceJwts.CONTROL_PLANE);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void theTokenEndpointAuthenticatesClientsAndRestrictsAudiences() throws Exception {
        Reply issued = client().serviceToken(RUNTIME_CLIENT, RUNTIME_SECRET, "control-plane");
        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.text("token_type")).isEqualTo("Bearer");
        assertThat(issued.json().get("expires_in").asInt()).isEqualTo(300);
        assertThat(issued.header("Cache-Control")).isEqualTo("no-store");

        Reply wrongSecret = client().serviceToken(RUNTIME_CLIENT, "not-the-secret", "control-plane");
        Reply unknown = client().serviceToken("someone", RUNTIME_SECRET, "control-plane");
        // 不带认证的请求不符合规范的 security 声明，绕过客户端的规范校验直接发送
        java.net.http.HttpResponse<String> anonymous = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(URI.create(url() + ServiceJwts.TOKEN_PATH))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                        "grant_type=client_credentials&audience=control-plane"))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        Reply notAllowed = client().serviceToken(EGRESS_CLIENT, EGRESS_SECRET, "control-plane");

        for (Reply rejected : new Reply[] {wrongSecret, unknown}) {
            assertThat(rejected.status()).isEqualTo(401);
            assertThat(rejected.text("code")).isEqualTo("auth.invalid_service_client");
        }
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("auth.invalid_service_client");
        assertThat(notAllowed.status()).isEqualTo(403);
        assertThat(notAllowed.text("code")).isEqualTo("access.forbidden");
    }

    @Test
    void expiredWrongAudienceAndUnlistedCallerTokensAreRejected() throws Exception {
        String tenant = "/internal/v1/tenants/ten_missing/quotas";
        // 刚过期：超过 30 秒的时钟偏差
        Reply expired = client().internal(
                        "GET",
                        tenant,
                        sign("runtime", "control-plane", Instant.now().minusSeconds(40)),
                        null);
        // 有效的令牌，但签给 runtime
        String forRuntime =
                client().serviceToken(EGRESS_CLIENT, EGRESS_SECRET, "runtime").text("access_token");
        Reply wrongAudience = client().internal("GET", tenant, forRuntime, null);
        // 签名、受众都对，但调用方不在本接口的允许列表中
        Reply unlisted = client().internal(
                        "GET",
                        tenant,
                        sign("egress-proxy", "control-plane", Instant.now().plusSeconds(300)),
                        null);
        Reply valid = client().internal("GET", tenant, serviceToken(), null);

        for (Reply rejected : new Reply[] {expired, wrongAudience}) {
            assertThat(rejected.status()).isEqualTo(401);
            assertThat(rejected.text("code")).isEqualTo("auth.invalid_service_credential");
        }
        assertThat(unlisted.status()).isEqualTo(403);
        assertThat(unlisted.text("code")).isEqualTo("access.forbidden");
        assertThat(valid.status()).isEqualTo(404);
        assertThat(valid.text("code")).isEqualTo("tenant.not_found");
    }

    @Test
    void afterKeyRotationRemoteVerifiersRefreshTheJwksAndOldTokensStillVerify() throws Exception {
        ServiceJwtVerifier runtimeSide = remoteVerifier("runtime");
        String before =
                client().serviceToken(EGRESS_CLIENT, EGRESS_SECRET, "runtime").text("access_token");
        assertThat(runtimeSide.resolve(before)).contains(new ServicePrincipal(EGRESS_CLIENT));
        String oldKid = keys.current().getKeyID();

        keys.rotate();

        Reply jwks = client().internal("GET", ServiceJwts.JWKS_PATH, null, null);
        assertThat(jwks.json().get("keys")).hasSize(2);
        assertThat(jwks.json().get("keys").get(0).get("kid").asString()).isNotEqualTo(oldKid);
        assertThat(jwks.json().get("keys").get(1).get("kid").asString()).isEqualTo(oldKid);
        String after =
                client().serviceToken(EGRESS_CLIENT, EGRESS_SECRET, "runtime").text("access_token");
        assertThat(SignedJWT.parse(after).getHeader().getKeyID()).isNotEqualTo(oldKid);
        // 校验端缓存里只有旧公钥：遇到新 kid 刷新一次 JWKS
        assertThat(runtimeSide.resolve(after)).contains(new ServicePrincipal(EGRESS_CLIENT));
        assertThat(runtimeSide.resolve(before)).contains(new ServicePrincipal(EGRESS_CLIENT));
        // control-plane 自己的校验器同样立即接受新密钥签的令牌
        assertThat(client().internal("GET", "/internal/v1/tenants/ten_missing/quotas", serviceToken(), null)
                        .status())
                .isEqualTo(404);
    }
}
