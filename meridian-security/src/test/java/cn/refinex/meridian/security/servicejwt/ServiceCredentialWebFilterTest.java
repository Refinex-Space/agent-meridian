package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.MeridianException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link ServiceCredentialWebFilter}：{@code /internal/} 下的请求必须带有效的服务凭据。 */
class ServiceCredentialWebFilterTest {

    private final ServiceCredentialWebFilter filter = new ServiceCredentialWebFilter(
            new ServiceCredentialAuthenticator(new DevServiceCredentialResolver(new DevServiceCredentialProperties(
                    true, List.of(new DevServiceCredentialProperties.Credential("svc_dev_a", "knowledge"))))));

    @ParameterizedTest
    @ValueSource(strings = {"/internal;a=b/v1/egress:authorize", "/%69nternal/v1/embeddings", "/internal;x/v1/rerank"})
    void pathVariantsThatRouteToInternalEndpointsStillRequireCredentials(String path) {
        // 路由按解码、去掉矩阵参数后的段匹配，过滤器必须按同样的值判断（R1 安全审查，ADR-0041）
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.POST, java.net.URI.create(path)));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .expectErrorSatisfies(error -> assertThat(((MeridianException) error).errorCode())
                        .isEqualTo(ServiceCredentialErrorCode.UNAUTHENTICATED))
                .verify();
    }

    @Test
    void handlersRejectRequestsThatBypassedTheFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/rerank"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> ServiceCredentialAuthorization.require(exchange)))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ServiceCredentialErrorCode.UNAUTHENTICATED));
    }

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
