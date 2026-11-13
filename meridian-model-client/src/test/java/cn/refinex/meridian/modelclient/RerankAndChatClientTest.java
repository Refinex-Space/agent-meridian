package cn.refinex.meridian.modelclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** {@link RerankClient} 与 {@link ChatClient}：请求体、结果解析、错误的重试分类。 */
class RerankAndChatClientTest {

    private MockWebServer runtime;
    private RestClient rest;

    @BeforeEach
    void start() throws Exception {
        runtime = new MockWebServer();
        runtime.start();
        rest = RestClient.builder().baseUrl(runtime.url("/").toString()).build();
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
    void rerankSendsCandidatesAndKeepsTheReturnedOrder() throws Exception {
        runtime.enqueue(json(200, """
                {"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}],
                 "model":"mdl_r","usage":{"prompt_tokens":30,"total_tokens":30}}"""));

        RerankResult result = new RerankClient(rest)
                .rerank(new RerankRequest("ten_a", "prj_a", "mdl_r", "住宿", List.of("报销", "住宿标准"), 2));

        assertThat(result.scores()).containsExactly(new RerankResult.Score(1, 0.9), new RerankResult.Score(0, 0.2));
        assertThat(result.promptTokens()).isEqualTo(30);
        RecordedRequest request = runtime.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/internal/v1/rerank");
        assertThat(request.getBody().utf8())
                .isEqualTo("{\"tenant_id\":\"ten_a\",\"project_id\":\"prj_a\",\"model\":\"mdl_r\",\"query\":\"住宿\","
                        + "\"documents\":[\"报销\",\"住宿标准\"],\"top_n\":2}");
    }

    @Test
    void rerankRejectsOutOfRangeIndexesAsRetryable() {
        runtime.enqueue(json(200, "{\"results\":[{\"index\":5,\"relevance_score\":0.9}],\"usage\":{}}"));
        runtime.enqueue(json(400, "{\"code\":\"model.unsupported_operation\",\"detail\":\"不是 rerank 模型\"}"));
        RerankClient client = new RerankClient(rest);
        RerankRequest request = new RerankRequest("ten_a", null, "mdl_r", "q", List.of("a"), null);

        assertThatThrownBy(() -> client.rerank(request))
                .isInstanceOfSatisfying(
                        ModelClientException.class,
                        e -> assertThat(e.retryable()).isTrue());
        assertThatThrownBy(() -> client.rerank(request)).isInstanceOfSatisfying(ModelClientException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.code()).isEqualTo("model.unsupported_operation");
        });
    }

    @Test
    void chatSendsMessagesAndReturnsText() throws Exception {
        runtime.enqueue(json(200, """
                {"content":"上海 出差 住宿 标准","model":"mdl_c",
                 "usage":{"prompt_tokens":40,"completion_tokens":6,"total_tokens":46}}"""));

        ChatResult result = new ChatClient(rest)
                .complete(new ChatRequest(
                        "ten_a",
                        "prj_a",
                        "mdl_c",
                        List.of(
                                new ChatRequest.Message(ChatRequest.Role.SYSTEM, "改写"),
                                new ChatRequest.Message(ChatRequest.Role.USER, "那上海呢？")),
                        64,
                        0.0));

        assertThat(result).isEqualTo(new ChatResult("上海 出差 住宿 标准", 40, 6));
        RecordedRequest request = runtime.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/internal/v1/chat/completions");
        assertThat(request.getBody().utf8())
                .isEqualTo("{\"tenant_id\":\"ten_a\",\"project_id\":\"prj_a\",\"model\":\"mdl_c\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"改写\"},{\"role\":\"user\",\"content\":\"那上海呢？\"}],"
                        + "\"max_tokens\":64,\"temperature\":0.0}");
    }

    @Test
    void requestsValidateTheirInputs() {
        assertThatThrownBy(() -> new RerankRequest("ten_a", null, "mdl_r", "", List.of("a"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankRequest("ten_a", null, "mdl_r", "q", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRequest("ten_a", null, "mdl_c", List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
