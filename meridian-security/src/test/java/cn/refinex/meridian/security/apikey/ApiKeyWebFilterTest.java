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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"/v1;x=y/sessions", "/%76%31/sessions"})
    void pathVariantsThatRouteToThePublicApiStillRequireAKey(String path) {
        // 与路由匹配一致：解码、去掉矩阵参数后的第一段是 v1 就要认证（ADR-0041）
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, java.net.URI.create(path)));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .expectError(MeridianException.class)
                .verify();
    }

    @Test
    void pathsOutsideThePublicApiAreNotAuthenticated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
    }

    @Test
    void rateLimitedKeysCarryHeadersAndAreRejectedWithRetryAfterWhenExhausted() {
        // 每分钟上限 2 的 Key：限流器按调用次数给出剩余量，第三次拒绝
        ApiKeyResolver resolver = key -> java.util.Optional.of(
                new ApiKeyPrincipal("key_rl", "ten_1", "prj_a", java.util.Set.of(ApiKeyScope.SESSIONS_READ), 2));
        java.util.concurrent.atomic.AtomicInteger used = new java.util.concurrent.atomic.AtomicInteger();
        ApiKeyRateLimiter limiter = (keyId, limit) -> {
            int n = used.incrementAndGet();
            return n <= limit
                    ? new RateLimitDecision(true, limit, limit - n, 30L * n, 0)
                    : new RateLimitDecision(false, limit, 0, 60, 30);
        };
        ApiKeyWebFilter limited = new ApiKeyWebFilter(new ApiKeyAuthenticator(resolver), null, limiter);

        for (int i = 1; i <= 2; i++) {
            MockServerWebExchange ok = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/v1/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer mk_live_x"));
            StepVerifier.create(limited.filter(ok, ex -> Mono.empty())).verifyComplete();
            assertThat(ok.getResponse().getHeaders().getFirst("RateLimit-Limit"))
                    .isEqualTo("2");
            assertThat(ok.getResponse().getHeaders().getFirst("RateLimit-Remaining"))
                    .isEqualTo(Integer.toString(2 - i));
            assertThat(ok.getResponse().getHeaders().getFirst("RateLimit-Reset"))
                    .isEqualTo(Integer.toString(30 * i));
        }
        MockServerWebExchange rejected = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer mk_live_x"));
        StepVerifier.create(limited.filter(rejected, ex -> Mono.empty()))
                .expectErrorSatisfies(error ->
                        assertThat(((MeridianException) error).errorCode()).isEqualTo(ApiKeyErrorCode.RATE_LIMITED))
                .verify();
        assertThat(rejected.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("30");
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Remaining"))
                .isEqualTo("0");
    }

    @Test
    void limiterFailuresLetTheRequestThroughWithoutHeaders() {
        ApiKeyResolver resolver = key -> java.util.Optional.of(
                new ApiKeyPrincipal("key_rl", "ten_1", "prj_a", java.util.Set.of(ApiKeyScope.SESSIONS_READ), 2));
        ApiKeyWebFilter limited = new ApiKeyWebFilter(new ApiKeyAuthenticator(resolver), null, (keyId, limit) -> {
            throw new IllegalStateException("Redis 不可用");
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer mk_live_x"));

        StepVerifier.create(limited.filter(exchange, ex -> Mono.empty())).verifyComplete();
        assertThat(exchange.getResponse().getHeaders().containsHeader("RateLimit-Limit"))
                .isFalse();
    }

    @Test
    void jwtShapedTokensGoToTheConsoleAuthenticatorWithTheProjectHeader() {
        // ADR-0044：OIDC 令牌加 Meridian-Project，按用户的桶限流，PlatformContext 带用户而不是 API Key
        java.util.List<String> buckets = new java.util.ArrayList<>();
        ApiKeyRateLimiter limiter = (bucket, limit) -> {
            buckets.add(bucket);
            return new RateLimitDecision(true, limit, limit - 1, 1, 0);
        };
        ConsoleTokenAuthenticator console = (token, project) -> new TestConsolePrincipal("alice", project);
        ApiKeyWebFilter both =
                new ApiKeyWebFilter(new ApiKeyAuthenticator(key -> java.util.Optional.empty()), console, limiter);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer eyJh.eyJz.c2ln")
                .header(ApiKeyWebFilter.PROJECT_HEADER, "prj_a"));
        AtomicReference<PlatformContext> seen = new AtomicReference<>();

        StepVerifier.create(both.filter(
                        exchange,
                        ex -> PlatformContexts.currentReactive()
                                .doOnNext(seen::set)
                                .then()))
                .verifyComplete();

        assertThat(seen.get().userId()).isEqualTo("alice");
        assertThat(seen.get().projectId()).isEqualTo("prj_a");
        assertThat(buckets).containsExactly("user:{alice}");
        assertThat(ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ)
                        .projectId())
                .isEqualTo("prj_a");
        // API Key 照常走 API Key 认证
        MockServerWebExchange apiKey = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer mk_live_x"));
        StepVerifier.create(both.filter(apiKey, ex -> Mono.empty()))
                .expectErrorSatisfies(error ->
                        assertThat(((MeridianException) error).errorCode()).isEqualTo(ApiKeyErrorCode.INVALID_API_KEY))
                .verify();
    }

    /** 测试用的控制台用户主体（真实实现在 oidc 包）。 */
    private record TestConsolePrincipal(String user, String projectId) implements PublicApiPrincipal {

        @Override
        public String tenantId() {
            return "ten_1";
        }

        @Override
        public java.util.Set<ApiKeyScope> scopes() {
            return java.util.Set.of(ApiKeyScope.SESSIONS_READ);
        }

        @Override
        public Integer rateLimitPerMinute() {
            return 600;
        }

        @Override
        public String rateLimitBucket() {
            return "user:{" + user + "}";
        }

        @Override
        public String describe() {
            return user;
        }

        @Override
        public PlatformContext toContext() {
            return PlatformContext.builder()
                    .tenantId("ten_1")
                    .projectId(projectId)
                    .userId(user)
                    .build();
        }
    }
}
