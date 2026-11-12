package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/** {@link ApiKeyFilter} 与 {@link PublicApiRequests}：MVC 服务的公开 API 与 WebFlux 版规则相同（D55）。 */
class ApiKeyFilterTest {

    private final List<Exception> resolved = new ArrayList<>();
    private final List<String> consumed = new ArrayList<>();

    private final ApiKeyFilter filter = new ApiKeyFilter(
            new PublicApiAdmission(
                    new ApiKeyAuthenticator(key -> switch (key) {
                        case "mk_live_reader" -> Optional.of(principal("key_r", null));
                        case "mk_live_limited" -> Optional.of(principal("key_l", 2));
                        default -> Optional.empty();
                    }),
                    null,
                    (bucket, limit) -> {
                        consumed.add(bucket);
                        boolean allowed = consumed.size() <= limit;
                        return new RateLimitDecision(allowed, limit, allowed ? limit - consumed.size() : 0, 30, 30);
                    }),
            (request, response, handler, error) -> {
                resolved.add(error);
                return new ModelAndView();
            });

    @Test
    void authenticatedRequestsBindThePlatformContextAndPrincipal() throws Exception {
        MockHttpServletRequest request = request("/v1/agents", "Bearer mk_live_reader");
        AtomicReference<PlatformContext> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(
                    jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seen.set(PlatformContexts.current());
            }
        });

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(resolved).isEmpty();
        assertThat(seen.get().projectId()).isEqualTo("prj_a");
        assertThat(seen.get().apiKeyId()).isEqualTo("key_r");
        assertThat(PlatformContexts.find()).as("绑定只在过滤链之内").isEmpty();
        assertThat(PublicApiRequests.require(request, ApiKeyScope.SESSIONS_READ).tenantId())
                .isEqualTo("ten_1");
        assertThatThrownBy(() -> PublicApiRequests.require(request, ApiKeyScope.SESSIONS_WRITE))
                .isInstanceOfSatisfying(
                        ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.INSUFFICIENT_SCOPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/agents", "/v1;x=y/agents", "/%76%31/agents"})
    void missingOrInvalidKeysAreRejectedOnEveryPathVariant(String path) throws Exception {
        for (String header : new String[] {null, "Bearer mk_live_unknown"}) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(path, header), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(path + " " + header).isNull();
        }
        assertThat(resolved).hasSize(2).allSatisfy(error -> assertThat(error).isInstanceOf(MeridianException.class));
    }

    @Test
    void requestsOverTheKeyLimitGet429WithRateLimitHeaders() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request("/v1/agents", "Bearer mk_live_limited"), first, new MockFilterChain());
        filter.doFilter(
                request("/v1/agents", "Bearer mk_live_limited"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse third = new MockHttpServletResponse();
        MockFilterChain blocked = new MockFilterChain();
        filter.doFilter(request("/v1/agents", "Bearer mk_live_limited"), third, blocked);

        assertThat(first.getHeader(PublicApiAdmission.LIMIT_HEADER)).isEqualTo("2");
        assertThat(first.getHeader(PublicApiAdmission.REMAINING_HEADER)).isEqualTo("1");
        assertThat(blocked.getRequest()).isNull();
        assertThat(third.getHeader("Retry-After")).isEqualTo("30");
        assertThat(resolved)
                .singleElement()
                .satisfies(
                        e -> assertThat(((MeridianException) e).errorCode()).isEqualTo(ApiKeyErrorCode.RATE_LIMITED));
        assertThat(consumed).hasSize(3).containsOnly(principal("key_l", 2).rateLimitBucket());
    }

    @Test
    void pathsOutsideThePublicApiAreNotAuthenticated() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        HttpServletRequest request = request("/admin/v1/tenants", null);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(resolved).isEmpty();
        assertThatThrownBy(() -> PublicApiRequests.require(request, ApiKeyScope.SESSIONS_READ))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.UNAUTHENTICATED));
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static ApiKeyPrincipal principal(String keyId, Integer limit) {
        return new ApiKeyPrincipal(keyId, "ten_1", "prj_a", Set.of(ApiKeyScope.SESSIONS_READ), limit);
    }
}
