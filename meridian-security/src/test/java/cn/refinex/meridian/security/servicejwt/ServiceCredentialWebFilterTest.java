package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.MeridianException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link ServiceCredentialWebFilter}：{@code /internal/} 下的请求必须带有效的服务凭据。 */
class ServiceCredentialWebFilterTest {

    private final ServiceCredentialWebFilter filter =
            new ServiceCredentialWebFilter(new DevServiceCredentialResolver(new DevServiceCredentialProperties(
                    true, List.of(new DevServiceCredentialProperties.Credential("svc_dev_a", "knowledge")))));

    @Test
    void validCredentialsPassAndRecordTheCallingService() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/rerank")
                .header(HttpHeaders.AUTHORIZATION, "Bearer svc_dev_a"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat((ServicePrincipal) exchange.getAttribute(ServiceCredentialWebFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new ServicePrincipal("knowledge"));
    }

    @Test
    void missingOrInvalidCredentialsAreRejected() {
        MockServerWebExchange missing =
                MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/embeddings"));
        MockServerWebExchange wrong = MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mk_dev_api_key"));

        StepVerifier.create(filter.filter(missing, ex -> Mono.empty()))
                .expectErrorSatisfies(error -> assertThat(((MeridianException) error).errorCode())
                        .isEqualTo(ServiceCredentialErrorCode.UNAUTHENTICATED))
                .verify();
        StepVerifier.create(filter.filter(wrong, ex -> Mono.empty()))
                .expectErrorSatisfies(error -> assertThat(((MeridianException) error).errorCode())
                        .isEqualTo(ServiceCredentialErrorCode.INVALID_SERVICE_CREDENTIAL))
                .verify();
    }

    @Test
    void otherPathsAreNotChecked() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
    }
}
