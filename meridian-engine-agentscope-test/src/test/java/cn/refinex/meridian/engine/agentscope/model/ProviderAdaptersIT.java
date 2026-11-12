package cn.refinex.meridian.engine.agentscope.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.InMemoryModelGateway;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import cn.refinex.meridian.engine.spi.port.ProviderRetryPolicy;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * Anthropic 与 Gemini 的平台适配器（T15a，ADR-0046）对 MockWebServer 模拟的两家流式接口，经真实的 {@link RoutedModel}：文本与工具
 * 调用的流、供应商报告的用量、每次调用的凭据（同一个底层客户端换凭据）、SDK 不自己重试（500 时请求数等于平台的 1 + 2 次重试）、
 * 429 的 {@code retry-after}、4xx 为拒绝；Gemini 不接受 URL 图片，也不发请求。本机没有两家的真实密钥，没有真实模型测试。
 */
class ProviderAdaptersIT {

    private static final PlatformContext PLATFORM = PlatformContext.builder()
            .tenantId("ten_t")
            .projectId("prj_t")
            .sessionId("ses_t")
            .threadId("thr_t")
            .turnId("trn_t")
            .leaseEpoch(1L)
            .build();
    private static final ProviderRetryPolicy FAST_RETRY =
            new ProviderRetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(2));
    private static final List<Msg> MESSAGES = List.of(
            Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text("你是测试助手。").build())
                    .build(),
            Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text("你好").build())
                    .build());
    private static final List<ToolSchema> TOOLS = List.of(ToolSchema.builder()
            .name("lookup")
            .description("查询订单")
            .parameters(Map.of("type", "object", "properties", Map.of("order", Map.of("type", "string"))))
            .build());

    private MockWebServer server;
    private InMemoryModelGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        gateway = new InMemoryModelGateway();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    // ---- Anthropic ----

    @Test
    void anthropicStreamsTextAndUsageWithTheCredentialOfEachCall() throws Exception {
        route("mdl_a1", Provider.ANTHROPIC, "claude-test", "key-one");
        route("mdl_a2", Provider.ANTHROPIC, "claude-test", "key-two");
        server.enqueue(anthropic(anthropicText("你好，我是助手")));
        server.enqueue(anthropic(anthropicText("第二次")));

        List<ChatResponse> first = call("mdl_a1", Provider.ANTHROPIC, null);
        List<ChatResponse> second = call("mdl_a2", Provider.ANTHROPIC, null);

        assertThat(text(first)).isEqualTo("你好，我是助手");
        assertThat(text(second)).isEqualTo("第二次");
        RecordedRequest one = take();
        RecordedRequest two = take();
        assertThat(one.getUrl().encodedPath()).isEqualTo("/v1/messages");
        // 同一个"账号加模型"的底层客户端，凭据随每次调用
        assertThat(one.getHeaders().get("x-api-key")).isEqualTo("key-one");
        assertThat(two.getHeaders().get("x-api-key")).isEqualTo("key-two");
        String body = one.getBody().utf8();
        assertThat(body)
                .contains("\"model\":\"claude-test\"")
                .contains("\"max_tokens\":256")
                .contains("\"system\":\"你是测试助手。\"")
                .contains("\"stream\":true");
        var record = gateway.settlements().getFirst();
        assertThat(record.usage().inputTokens()).isEqualTo(15);
        assertThat(record.usage().outputTokens()).isEqualTo(7);
        assertThat(record.usage().cachedTokens()).isEqualTo(3);
    }

    @Test
    void anthropicToolCallsArriveAsToolUseBlocks() throws Exception {
        route("mdl_a", Provider.ANTHROPIC, "claude-test", "key");
        server.enqueue(anthropic(String.join(
                "",
                event("message_start", messageStart()),
                event(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\","
                                + "\"id\":\"toolu_1\",\"name\":\"lookup\",\"input\":{}}}"),
                event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                                + "\"partial_json\":\"{\\\"order\\\":\\\"A1\\\"}\"}}"),
                event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                event(
                        "message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
                                + "\"usage\":{\"output_tokens\":9}}"),
                event("message_stop", "{\"type\":\"message_stop\"}"))));

        List<ChatResponse> responses = call("mdl_a", Provider.ANTHROPIC, TOOLS);

        assertThat(blocks(responses)).filteredOn(ToolUseBlock.class::isInstance).isNotEmpty();
        assertThat(take().getBody().utf8()).contains("\"tools\"").contains("\"name\":\"lookup\"");
    }

    @Test
    void anthropicServerErrorsAreRetriedOnlyByThePlatform() {
        route("mdl_a", Provider.ANTHROPIC, "claude-test", "key");
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse.Builder()
                    .code(500)
                    .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}")
                    .build());
        }

        Throwable error = failure("mdl_a", Provider.ANTHROPIC);

        assertThat(error).isInstanceOf(ProviderUnavailableException.class);
        // 1 次加平台的 2 次重试；SDK 自带的重试会让它变成 9
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(gateway.releases()).isEqualTo(1);
    }

    @Test
    void anthropicRateLimitsHonourRetryAfterAndClientErrorsAreRejected() throws Exception {
        route("mdl_a", Provider.ANTHROPIC, "claude-test", "key");
        server.enqueue(new MockResponse.Builder()
                .code(429)
                .addHeader("retry-after", "1")
                .body("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")
                .build());
        server.enqueue(anthropic(anthropicText("好了")));

        long started = System.nanoTime();
        assertThat(text(call("mdl_a", Provider.ANTHROPIC, null))).isEqualTo("好了");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(server.getRequestCount()).isEqualTo(2);

        server.enqueue(new MockResponse.Builder()
                .code(401)
                .body(
                        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")
                .build());
        Throwable rejected = failure("mdl_a", Provider.ANTHROPIC);
        assertThat(rejected).isInstanceOf(ProviderRejectedException.class).hasMessageContaining("401");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // ---- Gemini ----

    @Test
    void geminiStreamsTextAndUsageWithTheCredentialOfEachCall() throws Exception {
        route("mdl_g1", Provider.GEMINI, "gemini-test", "g-key-one");
        route("mdl_g2", Provider.GEMINI, "gemini-test", "g-key-two");
        server.enqueue(gemini(geminiChunk("{\"text\":\"你好\"}", null)
                + geminiChunk(
                        "{\"text\":\"，我是助手\"}",
                        "\"usageMetadata\":{\"promptTokenCount\":12,"
                                + "\"candidatesTokenCount\":5,\"totalTokenCount\":17}")));
        server.enqueue(gemini(geminiChunk("{\"text\":\"第二次\"}", null)));

        List<ChatResponse> first = call("mdl_g1", Provider.GEMINI, null);
        List<ChatResponse> second = call("mdl_g2", Provider.GEMINI, null);

        assertThat(text(first)).isEqualTo("你好，我是助手");
        assertThat(text(second)).isEqualTo("第二次");
        RecordedRequest one = take();
        RecordedRequest two = take();
        assertThat(one.getUrl().encodedPath()).endsWith("/models/gemini-test:streamGenerateContent");
        assertThat(one.getHeaders().get("x-goog-api-key")).isEqualTo("g-key-one");
        assertThat(two.getHeaders().get("x-goog-api-key")).isEqualTo("g-key-two");
        assertThat(one.getBody().utf8()).contains("\"maxOutputTokens\":256");
        var record = gateway.settlements().getFirst();
        assertThat(record.usage().inputTokens()).isEqualTo(12);
        assertThat(record.usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void geminiFunctionCallsArriveAsToolUseBlocks() throws Exception {
        route("mdl_g", Provider.GEMINI, "gemini-test", "g-key");
        server.enqueue(
                gemini(geminiChunk("{\"functionCall\":{\"name\":\"lookup\",\"args\":{\"order\":\"A1\"}}}", null)));

        List<ChatResponse> responses = call("mdl_g", Provider.GEMINI, TOOLS);

        assertThat(blocks(responses))
                .filteredOn(ToolUseBlock.class::isInstance)
                .singleElement()
                .satisfies(block -> {
                    assertThat(((ToolUseBlock) block).getName()).isEqualTo("lookup");
                    assertThat(((ToolUseBlock) block).getInput()).containsEntry("order", "A1");
                });
        assertThat(take().getBody().utf8()).contains("\"functionDeclarations\"");
    }

    @Test
    void geminiErrorsAreClassifiedAndNotRetriedByTheSdk() {
        route("mdl_g", Provider.GEMINI, "gemini-test", "g-key");
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse.Builder()
                    .code(503)
                    .body("{\"error\":{\"code\":503,\"message\":\"overloaded\",\"status\":\"UNAVAILABLE\"}}")
                    .build());
        }

        assertThat(failure("mdl_g", Provider.GEMINI)).isInstanceOf(ProviderUnavailableException.class);
        // SDK 默认 5 次尝试；attempts(1) 之后只有平台的 1 + 2 次
        assertThat(server.getRequestCount()).isEqualTo(3);

        server.enqueue(new MockResponse.Builder()
                .code(400)
                .body("{\"error\":{\"code\":400,\"message\":\"bad\",\"status\":\"INVALID_ARGUMENT\"}}")
                .build());
        assertThat(failure("mdl_g", Provider.GEMINI)).isInstanceOf(ProviderRejectedException.class);
        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    void geminiRejectsUrlImagesWithoutDownloadingOrCalling() {
        route("mdl_g", Provider.GEMINI, "gemini-test", "g-key");
        List<Msg> withImage = List.of(Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(
                        TextBlock.builder().text("看图").build(),
                        ImageBlock.builder()
                                .source(URLSource.builder()
                                        .url(server.url("/secret.png").toString())
                                        .build())
                                .build()))
                .build());

        Throwable error = null;
        try {
            model("mdl_g", Provider.GEMINI).stream(withImage, null, null)
                    .contextWrite(PlatformContexts.write(PLATFORM))
                    .collectList()
                    .block(Duration.ofSeconds(10));
        } catch (RuntimeException e) {
            error = Exceptions.unwrap(e);
        }

        assertThat(error).isInstanceOf(ProviderRejectedException.class).hasMessageContaining("URL");
        assertThat(server.getRequestCount()).isZero();
    }

    // ---- 辅助 ----

    private void route(String modelId, Provider provider, String upstreamModel, String apiKey) {
        gateway.route(new ModelRoute(
                modelId,
                "prv_shared_" + provider.value(),
                provider,
                provider == Provider.ANTHROPIC
                        ? server.url("/").toString().replaceAll("/$", "")
                        : server.url("/").toString(),
                upstreamModel,
                new SecretValue(apiKey),
                32_000,
                256));
    }

    private RoutedModel model(String modelId, Provider provider) {
        return (RoutedModel) new RoutedModelFactory(
                        gateway,
                        new ProviderClients(List.of(
                                new AnthropicClientFactory(Clock.systemUTC()),
                                new GeminiClientFactory(Clock.systemUTC()))),
                        FAST_RETRY)
                .create("agv_t", new ModelSpec(modelId, null, null, null));
    }

    private List<ChatResponse> call(String modelId, Provider provider, List<ToolSchema> tools) {
        return model(modelId, provider).stream(MESSAGES, tools, null)
                .contextWrite(PlatformContexts.write(PLATFORM))
                .collectList()
                .block(Duration.ofSeconds(20));
    }

    private Throwable failure(String modelId, Provider provider) {
        try {
            call(modelId, provider, null);
        } catch (RuntimeException e) {
            return Exceptions.unwrap(e);
        }
        throw new AssertionError("调用应当失败");
    }

    private RecordedRequest take() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static List<ContentBlock> blocks(List<ChatResponse> responses) {
        return responses.stream().flatMap(r -> r.getContent().stream()).toList();
    }

    private static String text(List<ChatResponse> responses) {
        return blocks(responses).stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .reduce("", String::concat);
    }

    private static MockResponse anthropic(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build();
    }

    private static String anthropicText(String text) {
        return String.join(
                "",
                event("message_start", messageStart()),
                event(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
                                + text + "\"}}"),
                event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                event(
                        "message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                                + "\"usage\":{\"output_tokens\":7}}"),
                event("message_stop", "{\"type\":\"message_stop\"}"));
    }

    private static String messageStart() {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-test\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":1,\"cache_read_input_tokens\":3,"
                + "\"cache_creation_input_tokens\":0}}}";
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private static MockResponse gemini(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build();
    }

    private static String geminiChunk(String part, String extra) {
        return "data: {\"candidates\":[{\"content\":{\"parts\":[" + part + "],\"role\":\"model\"},\"index\":0"
                + (extra == null ? "" : ",\"finishReason\":\"STOP\"") + "}]"
                + (extra == null ? "" : "," + extra) + ",\"modelVersion\":\"gemini-test\"}\n\n";
    }
}
