package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import java.util.Optional;
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
            new ServiceCredentialAuthenticator(credential -> "jwt_for_knowledge".equals(credential)
                    ? Optional.of(new ServicePrincipal("knowledge"))
                    : Optional.empty()));

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
                        () -> ServiceCredentialAuthorization.require(exchange, "knowledge")))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ServiceCredentialErrorCode.UNAUTHENTICATED));
    }

    @Test
    void validCredentialsPassAndRecordTheCallingService() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/rerank")
                .header(HttpHeaders.AUTHORIZATION, "Bearer jwt_for_knowledge"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat((ServicePrincipal) exchange.getAttribute(ServiceCredentialWebFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new ServicePrincipal("knowledge"));
        assertThat(ServiceCredentialAuthorization.require(exchange, "runtime", "knowledge"))
                .isEqualTo(new ServicePrincipal("knowledge"));
    }

    @Test
    void handlersRejectCallersThatAreNotListed() {
        // 每个内部接口只接受列明的调用方：出网代理的令牌不能调用 Embedding（ADR-0044）
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/internal/v1/embeddings"));
        exchange.getAttributes()
                .put(ServiceCredentialWebFilter.PRINCIPAL_ATTRIBUTE, new ServicePrincipal("egress-proxy"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> ServiceCredentialAuthorization.require(exchange, "knowledge")))
                .isInstanceOfSatisfying(
                        ForbiddenException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
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
