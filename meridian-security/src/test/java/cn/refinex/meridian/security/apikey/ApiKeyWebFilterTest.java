package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link ApiKeyWebFilter} 与 {@link ApiKeyAuthorization}。 */
class ApiKeyWebFilterTest {

    private final ApiKeyWebFilter filter =
            new ApiKeyWebFilter(new ApiKeyAuthenticator(new DevApiKeyResolver(new DevApiKeyProperties(
                    true,
                    List.of(new DevApiKeyProperties.Key(
                            "mk_dev_a", "key_a", "ten_1", "prj_a", List.of("sessions:read")))))));

    @Test
    void authenticatedRequestsCarryThePlatformContextAndPrincipal() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer mk_dev_a"));
        AtomicReference<PlatformContext> seen = new AtomicReference<>();

        StepVerifier.create(filter.filter(
                        exchange,
                        ex -> PlatformContexts.currentReactive()
                                .doOnNext(seen::set)
                                .then()))
                .verifyComplete();

        assertThat(seen.get().projectId()).isEqualTo("prj_a");
        assertThat(seen.get().apiKeyId()).isEqualTo("key_a");
        assertThat(ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ)
                        .tenantId())
                .isEqualTo("ten_1");
        assertThatThrownBy(() -> ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE))
                .isInstanceOfSatisfying(
                        ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.INSUFFICIENT_SCOPE));
    }

    @Test
    void apiRequestsWithoutAValidKeyAreRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .expectErrorSatisfies(error ->
                        assertThat(((MeridianException) error).errorCode()).isEqualTo(ApiKeyErrorCode.UNAUTHENTICATED))
                .verify();
        assertThatThrownBy(() -> ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ))
                .isInstanceOf(MeridianException.class);
    }

    @Test
    void pathsOutsideThePublicApiAreNotAuthenticated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
    }
}
