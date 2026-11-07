package cn.refinex.meridian.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.refinex.meridian.common.json.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/** T02-A02：MVC 与 WebFlux 的 problem+json 结构完全相同。 */
class ProblemAdaptersTest {

    private static final AtomicReference<String> MVC_MDC = new AtomicReference<>();
    private static final AtomicReference<String> WEBFLUX_MDC = new AtomicReference<>();

    private static MockMvc mvc;
    private static WebTestClient webflux;
    private static AnnotationConfigApplicationContext webfluxContext;

    @RestController
    static class MvcController {
        @GetMapping("/v1/problems/{scenario}")
        String fail(@PathVariable String scenario) {
            throw ProblemScenarios.failure(scenario);
        }

        @GetMapping("/v1/ok")
        String ok() {
            MVC_MDC.set(MDC.get(RequestIds.MDC_KEY));
            return "ok";
        }
    }

    @RestController
    static class WebFluxController {
        @GetMapping("/v1/problems/{scenario}")
        Mono<String> fail(@PathVariable String scenario) {
            return Mono.error(ProblemScenarios.failure(scenario));
        }

        @GetMapping("/v1/ok")
        Mono<String> ok() {
            return Mono.just("ok").map(value -> {
                WEBFLUX_MDC.set(MDC.get(RequestIds.MDC_KEY));
                return value;
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebFluxConfig {
        @Bean
        WebFluxController controller() {
            return new WebFluxController();
        }

        @Bean
        RequestIdWebFilter requestIdWebFilter() {
            return new RequestIdWebFilter();
        }

        @Bean
        WebFluxProblemHandler problemHandler() {
            return new WebFluxProblemHandler(new ProblemMapper());
        }
    }

    @BeforeAll
    static void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MvcController())
                .setControllerAdvice(new MvcProblemHandler(new ProblemMapper()))
                .addFilters(new RequestIdFilter())
                .build();
        webfluxContext = new AnnotationConfigApplicationContext(WebFluxConfig.class);
        webflux = WebTestClient.bindToApplicationContext(webfluxContext).build();
        RequestIdMdcAccessor.register();
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void tearDown() {
        Hooks.disableAutomaticContextPropagation();
        webfluxContext.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/problems/not-found", "/v1/problems/invalid", "/v1/problems/boom", "/v1/missing"})
    void bothStacksProduceIdenticalProblemJson(String path) throws Exception {
        Response fromMvc = mvc(path, "req-123");
        Response fromWebFlux = webflux(path, "req-123");

        assertThat(fromMvc.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(fromWebFlux.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(fromMvc.status()).isEqualTo(fromWebFlux.status());
        assertThat(fromMvc.body()).isEqualTo(fromWebFlux.body());
        assertThat(fromMvc.body())
                .containsKeys("type", "title", "status", "detail", "code", "request_id")
                .containsEntry("request_id", "req-123")
                .containsEntry("instance", path);
        assertThat(fromMvc.requestIdHeader()).isEqualTo("req-123");
        assertThat(fromWebFlux.requestIdHeader()).isEqualTo("req-123");
    }

    @Test
    void domainErrorIsMappedWithItsCode() throws Exception {
        Map<String, Object> body = mvc("/v1/problems/not-found", "req-1").body();

        assertThat(body)
                .containsEntry("type", "urn:meridian:error:session.not_found")
                .containsEntry("title", "会话不存在")
                .containsEntry("status", 404)
                .containsEntry("detail", "会话 ses_x 不存在")
                .containsEntry("code", "session.not_found");
    }

    @Test
    void validationErrorListsFields() throws Exception {
        Map<String, Object> body = webflux("/v1/problems/invalid", "req-1").body();

        assertThat(body).containsEntry("code", "request.validation_failed");
        assertThat(body.get("errors")).isEqualTo(java.util.List.of(Map.of("field", "agent_id", "message", "不能为空")));
    }

    @Test
    void unexpectedErrorDoesNotLeakInternals() throws Exception {
        Response response = webflux("/v1/problems/boom", "req-1");

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).containsEntry("code", "internal.error");
        assertThat(response.body().toString()).doesNotContain("secret", "口令");
    }

    @Test
    void unknownRouteIsProblemJsonNotFound() throws Exception {
        assertThat(mvc("/v1/missing", "req-1").body()).containsEntry("code", "resource.not_found");
        assertThat(webflux("/v1/missing", "req-1").body()).containsEntry("code", "resource.not_found");
    }

    @Test
    void invalidIncomingRequestIdIsReplaced() throws Exception {
        Response response = mvc("/v1/problems/not-found", "bad id\r\nX-Injected: 1");

        assertThat(response.requestIdHeader()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(response.body()).containsEntry("request_id", response.requestIdHeader());
    }

    @Test
    void requestIdIsGeneratedAndWrittenToMdcOnBothStacks() throws Exception {
        MvcResult mvcResult = mvc.perform(get("/v1/ok")).andReturn();
        String mvcId = mvcResult.getResponse().getHeader(RequestIds.HEADER);
        EntityExchangeResult<byte[]> webfluxResult = webflux.get()
                .uri("/v1/ok")
                .header(RequestIds.HEADER, "trace-from-gateway")
                .exchange()
                .expectBody()
                .returnResult();

        assertThat(mvcId).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(MVC_MDC).hasValue(mvcId);
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
        assertThat(webfluxResult.getResponseHeaders().getFirst(RequestIds.HEADER))
                .isEqualTo("trace-from-gateway");
        assertThat(WEBFLUX_MDC).hasValue("trace-from-gateway");
    }

    private static Response mvc(String path, String requestId) throws Exception {
        MvcResult result =
                mvc.perform(get(path).header(RequestIds.HEADER, requestId)).andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentType(),
                result.getResponse().getHeader(RequestIds.HEADER),
                parse(result.getResponse().getContentAsByteArray()));
    }

    private static Response webflux(String path, String requestId) {
        EntityExchangeResult<byte[]> result = webflux.get()
                .uri(path)
                .header(RequestIds.HEADER, requestId)
                .exchange()
                .expectBody()
                .returnResult();
        MediaType contentType = result.getResponseHeaders().getContentType();
        return new Response(
                result.getStatus().value(),
                contentType == null ? null : contentType.toString(),
                result.getResponseHeaders().getFirst(RequestIds.HEADER),
                parse(result.getResponseBody()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(byte[] body) {
        return JsonMappers.lenient().readValue(new String(body, StandardCharsets.UTF_8), Map.class);
    }

    record Response(int status, String contentType, String requestIdHeader, Map<String, Object> body) {}
}
