package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link ServiceTokenClient}：按目标服务缓存令牌，过期前 60 秒换新；换取失败是 503。 */
class ServiceTokenClientTest {

    private final TestTokenIssuer issuer = TestTokenIssuer.start().client("runtime", "runtime-secret");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final ServiceClientProperties properties =
            new ServiceClientProperties(issuer.url(), Duration.ofSeconds(2), Duration.ofSeconds(5));

    @AfterEach
    void stop() {
        issuer.close();
    }

    private ServiceTokenClient client(String secret) {
        return new ServiceTokenClient(
                ServiceRestClients.builder(issuer.url(), properties).build(), "runtime", secret, clock);
    }

    @Test
    void tokensAreCachedPerAudienceAndRefreshedBeforeExpiry() {
        ServiceTokenClient client = client("runtime-secret");

        String first = client.credential("control-plane");
        assertThat(client.credential("control-plane")).isEqualTo(first);
        assertThat(issuer.tokenRequests()).isEqualTo(1);
        assertThat(ServiceJwtVerifierTest.verifier(issuer, "control-plane").resolve(first))
                .contains(new ServicePrincipal("runtime"));

        client.credential("knowledge");
        assertThat(issuer.tokenRequests()).isEqualTo(2);

        // 有效 300 秒：239 秒时仍用缓存，240 秒（剩 60 秒）时换新
        now.set(now.get().plusSeconds(239));
        client.credential("control-plane");
        assertThat(issuer.tokenRequests()).isEqualTo(2);
        now.set(now.get().plusSeconds(1));
        client.credential("control-plane");
        assertThat(issuer.tokenRequests()).isEqualTo(3);
    }

    @Test
    void aWrongSecretIsAnOutageForTheCaller() {
        ServiceTokenClient client = client("wrong");

        assertThatThrownBy(() -> client.credential("control-plane"))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        // 失败不缓存，下次重新换取
        assertThatThrownBy(() -> client.credential("control-plane")).isInstanceOf(MeridianException.class);
        assertThat(issuer.tokenRequests()).isEqualTo(2);
    }

    @Test
    void restClientsSendTheTokenForTheTargetService() {
        ServiceRestClients clients = new ServiceRestClients(properties, client("runtime-secret"));

        String jwks = clients.create(issuer.url(), "control-plane")
                .get()
                .uri(ServiceJwts.JWKS_PATH)
                .retrieve()
                .body(String.class);

        assertThat(jwks).contains("\"keys\"");
        assertThat(issuer.tokenRequests()).isEqualTo(1);
    }
}
