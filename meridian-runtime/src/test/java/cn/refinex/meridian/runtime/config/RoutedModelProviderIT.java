package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code RoutedModel} 对模拟供应商（MockWebServer，OpenAI 兼容的 SSE）的错误处理，走真实的 AgentScope {@code OpenAIChatModel}、
 * 平台 HTTP 传输与 {@code RoutingModelGateway}（Redis 限流与预算、outbox 用量），见架构设计「模型调用」（T09a-A01）。
 */
class RoutedModelProviderIT {

    private static final String PRIMARY = "mdl_primary";
    private static final String FALLBACK = "mdl_fallback";
    private static final List<Msg> MESSAGES = List.of(Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("你好").build())
            .build());

    @TempDir
    Path directory;

    private MockWebServer server;
    private ModelRoutingFixture fixture;

    @BeforeEach
    void startProvider() throws Exception {
        server = new MockWebServer();
        server.start();
        fixture = new ModelRoutingFixture(directory, server.url("/v1").toString())
                .model(PRIMARY, "up-primary", 32_000, 256)
                .model(FALLBACK, "up-fallback", 16_000, 128);
    }

    @AfterEach
    void stopProvider() throws Exception {
        fixture.close();
        server.close();
    }

    @Test
    void serverErrorsBeforeTheFirstChunkAreRetriedThenSwitchToTheFallbackModel() throws Exception {
        server.enqueue(status(503));
        server.enqueue(status(502));
        server.enqueue(status(500));
        server.enqueue(sse("up-fallback", "备用模型的回答", 20, 6, 0, 0));
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, FALLBACK, null, null), ModelRoutingFixture.fastRetry());

        String text = textOf(call(model, fixture.context("ses_" + Ids.newUlid())));

        assertThat(text).isEqualTo("备用模型的回答");
        List<String> requestedModels =
                takeRequests(4).stream().map(RoutedModelProviderIT::modelOf).toList();
        assertThat(requestedModels)
                .as("首次加两次重试打到主模型，之后切到备用模型")
                .containsExactly("up-primary", "up-primary", "up-primary", "up-fallback");
        assertThat(fixture.usage()).singleElement().satisfies(usage -> {
            assertThat(usage).containsEntry("model_id", FALLBACK).containsEntry("fallback", true);
            assertThat(usage).containsEntry("input_tokens", 20).containsEntry("output_tokens", 6);
        });
    }

    @Test
    void anErrorAfterTheFirstChunkIsNeitherRetriedNorSwitched() throws Exception {
        String body = sseBody("up-primary", "前半段回答", 0, 0, 0, 0) + "x".repeat(4_096);
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .onResponseBody(SocketEffect.ShutdownConnection.INSTANCE)
                .build());
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, FALLBACK, null, null), ModelRoutingFixture.fastRetry());
        List<ChatResponse> received = new ArrayList<>();

        assertThatThrownBy(() -> model.stream(MESSAGES, null, null)
                        .doOnNext(received::add)
                        .contextWrite(PlatformContexts.write(fixture.context("ses_" + Ids.newUlid())))
                        .blockLast(Duration.ofSeconds(30)))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("响应中途断开");

        assertThat(textOf(received)).as("首包已经交给智能体").isEqualTo("前半段回答");
        assertThat(server.getRequestCount()).as("首包之后不重试、不切换").isEqualTo(1);
        assertThat(fixture.usage()).singleElement().satisfies(usage -> {
            assertThat(usage).containsEntry("usage_source", "estimated").containsEntry("fallback", false);
        });
    }

    @Test
    void tooManyRequestsWaitsForRetryAfterBeforeRetrying() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "1")
                .body("{\"error\":{\"message\":\"rate limited\"}}")
                .build());
        server.enqueue(sse("up-primary", "等了一秒", 8, 3, 0, 0));
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, null, null, null), ModelRoutingFixture.fastRetry());

        long start = System.nanoTime();
        String text = textOf(call(model, fixture.context("ses_" + Ids.newUlid())));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(text).isEqualTo("等了一秒");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(elapsed).as("退避只有 10 毫秒，等待来自 Retry-After").isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void budgetExceededNeitherCallsTheProviderNorSwitches() {
        PlatformContext context = fixture.context("ses_" + Ids.newUlid());
        fixture.budget("project", fixture.projectId, 10);
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, FALLBACK, null, null), ModelRoutingFixture.fastRetry());

        assertThatThrownBy(() -> call(model, context))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("项目预算不足");

        assertThat(server.getRequestCount()).isZero();
        assertThat(fixture.usage()).isEmpty();
        assertThat(fixture.registry
                        .get("meridian.budget.rejections")
                        .tag("level", "project")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void rateLimitTimeoutNeitherRetriesNorSwitches() throws Exception {
        fixture.rateLimit(1, 0);
        server.enqueue(sse("up-primary", "第一次", 8, 2, 0, 0));
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, FALLBACK, null, null), ModelRoutingFixture.fastRetry());
        call(model, fixture.context("ses_" + Ids.newUlid()));

        long start = System.nanoTime();
        assertThatThrownBy(() -> call(model, fixture.context("ses_" + Ids.newUlid())))
                .isInstanceOf(RateLimitTimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(server.getRequestCount()).as("限流异常不重试、不切换到备用模型").isEqualTo(1);
        assertThat(elapsed).as("令牌桶要 60 秒才补满，超过 30 秒的上限，不空等").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void credentialsAreSentPerCallAndUsageIsRecordedWithCacheAndReasoningTokens() throws Exception {
        server.enqueue(sse("up-primary", "好的", 120, 40, 64, 16));
        RoutedModel model =
                fixture.routedModel(new ModelSpec(PRIMARY, null, null, null), ModelRoutingFixture.fastRetry());

        call(model, fixture.context("ses_" + Ids.newUlid()));

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + ModelRoutingFixture.SECRET_VALUE);
        assertThat(request.getBody().utf8())
                .contains("\"stream\":true")
                .contains("\"max_tokens\":256")
                .contains("\"include_usage\":true");
        assertThat(fixture.usage())
                .singleElement()
                .satisfies(usage -> assertThat(usage)
                        .containsEntry("usage_source", "provider")
                        .containsEntry("input_tokens", 120)
                        .containsEntry("output_tokens", 40)
                        .containsEntry("cache_read_tokens", 64)
                        .containsEntry("reasoning_tokens", 16));
    }

    private static List<ChatResponse> call(RoutedModel model, PlatformContext context) {
        return model.stream(MESSAGES, null, null)
                .contextWrite(PlatformContexts.write(context))
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    private List<RecordedRequest> takeRequests(int count) throws InterruptedException {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(server.takeRequest(5, TimeUnit.SECONDS));
        }
        return requests;
    }

    private static String modelOf(RecordedRequest request) {
        String body = request.getBody().utf8();
        int start = body.indexOf("\"model\":\"") + "\"model\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static String textOf(List<ChatResponse> responses) {
        return responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    private static MockResponse status(int code) {
        return new MockResponse.Builder()
                .code(code)
                .body("{\"error\":{\"message\":\"upstream\"}}")
                .build();
    }

    static MockResponse sse(String model, String text, int prompt, int completion, int cached, int reasoning) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sseBody(model, text, prompt, completion, cached, reasoning) + "data: [DONE]\n\n")
                .build();
    }

    /** OpenAI 兼容的流式响应：一个内容增量，加上 DashScope 风格的末尾用量块（{@code choices} 为空）。 */
    static String sseBody(String model, String text, int prompt, int completion, int cached, int reasoning) {
        String chunk = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"%s",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":"%s"},"finish_reason":null}]}""".formatted(model, text);
        String finish = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"%s",\
                "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""".formatted(model);
        StringBuilder body = new StringBuilder()
                .append("data: ")
                .append(chunk)
                .append("\n\n")
                .append("data: ")
                .append(finish)
                .append("\n\n");
        if (prompt > 0) {
            String usage = """
                    {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"%s","choices":[],\
                    "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d,\
                    "prompt_tokens_details":{"cached_tokens":%d},"completion_tokens_details":{"reasoning_tokens":%d}}}""".formatted(model, prompt, completion, prompt + completion, cached, reasoning);
            body.append("data: ").append(usage).append("\n\n");
        }
        return body.toString();
    }
}
