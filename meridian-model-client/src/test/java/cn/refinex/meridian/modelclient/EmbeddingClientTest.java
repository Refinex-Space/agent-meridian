package cn.refinex.meridian.modelclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** {@link EmbeddingClient}：请求体、按序号排列结果、错误的重试分类。 */
class EmbeddingClientTest {

    private MockWebServer runtime;
    private EmbeddingClient client;

    @BeforeEach
    void start() throws Exception {
        runtime = new MockWebServer();
        runtime.start();
        client = new EmbeddingClient(
                RestClient.builder().baseUrl(runtime.url("/").toString()).build());
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", status < 400 ? "application/json" : "application/problem+json")
                .body(body)
                .build();
    }

    @Test
    void sendsTheBatchAndOrdersVectorsByIndex() throws Exception {
        runtime.enqueue(json(200, """
                {"data":[{"index":1,"embedding":[0.5,0.25]},{"index":0,"embedding":[1.0,0.0]}],
                 "model":"mdl_e","usage":{"prompt_tokens":12,"total_tokens":12}}"""));

        EmbeddingResult result =
                client.embed(new EmbeddingRequest("ten_a", "prj_a", "mdl_e", List.of("住宿标准", "交通费"), 2));

        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).containsExactly(1.0f, 0.0f);
        assertThat(result.vectors().get(1)).containsExactly(0.5f, 0.25f);
        assertThat(result.promptTokens()).isEqualTo(12);
        RecordedRequest request = runtime.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/internal/v1/embeddings");
        assertThat(request.getBody().utf8())
                .isEqualTo("{\"tenant_id\":\"ten_a\",\"project_id\":\"prj_a\",\"model\":\"mdl_e\","
                        + "\"input\":[\"住宿标准\",\"交通费\"],\"dimensions\":2}");
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryableOtherRejectionsAreNot() {
        runtime.enqueue(json(429, "{\"code\":\"model.budget_exceeded\",\"detail\":\"预算不足\"}"));
        runtime.enqueue(json(503, "{\"code\":\"model.provider_unavailable\",\"detail\":\"供应商不可用\"}"));
        runtime.enqueue(json(404, "{\"code\":\"model.not_found\",\"detail\":\"模型不存在\"}"));
        runtime.enqueue(json(502, "<html>bad gateway</html>"));
        EmbeddingRequest request = new EmbeddingRequest("ten_a", "prj_a", "mdl_e", List.of("a"), null);

        assertThatThrownBy(() -> client.embed(request)).isInstanceOfSatisfying(ModelClientException.class, e -> {
            assertThat(e.retryable()).isTrue();
            assertThat(e.code()).isEqualTo("model.budget_exceeded");
        });
        assertThatThrownBy(() -> client.embed(request))
                .isInstanceOfSatisfying(
                        ModelClientException.class,
                        e -> assertThat(e.retryable()).isTrue());
        assertThatThrownBy(() -> client.embed(request)).isInstanceOfSatisfying(ModelClientException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.code()).isEqualTo("model.not_found");
        });
        assertThatThrownBy(() -> client.embed(request)).isInstanceOfSatisfying(ModelClientException.class, e -> {
            assertThat(e.retryable()).isTrue();
            assertThat(e.code()).isEqualTo(ModelClientException.UNAVAILABLE);
        });
    }

    @Test
    void connectionFailuresAndIncompleteResponsesAreRetryable() {
        runtime.enqueue(json(200, "{\"data\":[{\"index\":0,\"embedding\":[1.0]}],\"usage\":{\"prompt_tokens\":1}}"));
        EmbeddingRequest two = new EmbeddingRequest("ten_a", null, "mdl_e", List.of("a", "b"), null);
        assertThatThrownBy(() -> client.embed(two))
                .isInstanceOfSatisfying(
                        ModelClientException.class,
                        e -> assertThat(e.retryable()).isTrue());

        runtime.close();
        assertThatThrownBy(() -> client.embed(two))
                .isInstanceOfSatisfying(
                        ModelClientException.class,
                        e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void batchesAreLimitedTo64NonEmptyInputs() {
        assertThatThrownBy(() -> new EmbeddingRequest("ten_a", null, "m", Collections.nCopies(65, "x"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRequest("ten_a", null, "m", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRequest("ten_a", null, "m", List.of(""), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EmbeddingRequest("ten_a", null, "m", Collections.nCopies(64, "x"), null).inputs())
                .hasSize(64);
    }
}
