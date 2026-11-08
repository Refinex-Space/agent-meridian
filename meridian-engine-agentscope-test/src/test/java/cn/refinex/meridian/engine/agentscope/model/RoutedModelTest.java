package cn.refinex.meridian.engine.agentscope.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.InMemoryModelGateway;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.ModelGateway.CallPurpose;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelCallRecord;
import cn.refinex.meridian.engine.spi.port.ModelGateway.UsageSource;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * {@link RoutedModel} 的路由、重试、降级与计量（架构设计「模型调用」，T09a-A01 的逻辑部分；HTTP 层的 5xx、断流与
 * {@code Retry-After} 由 runtime 的 {@code RoutedModelProviderIT} 用模拟供应商验证）。
 */
class RoutedModelTest {

    private static final String PRIMARY = "mdl_primary";
    private static final String FALLBACK = "mdl_fallback";
    private static final int WINDOW = 32_000;
    private static final int MAX_OUTPUT = 1_000;
    private static final PlatformContext PLATFORM = PlatformContext.builder()
            .tenantId("ten_t")
            .projectId("prj_t")
            .sessionId("ses_t")
            .threadId("thr_t")
            .turnId("trn_t")
            .leaseEpoch(1L)
            .build();
    private static final RoutedModel.RetryPolicy FAST_RETRY =
            new RoutedModel.RetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(1));
    private static final List<Msg> MESSAGES = List.of(Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("你好").build())
            .build());

    private final InMemoryModelGateway gateway = new InMemoryModelGateway()
            .route(PRIMARY, "up-primary", WINDOW, MAX_OUTPUT)
            .route(FALLBACK, "up-fallback", 16_000, 500);
    private final StaticProviderClientFactory providers = new StaticProviderClientFactory();

    @Test
    void successfulCallReservesRateLimitsAndSettlesWithProviderUsage() {
        providers.register(
                "up-primary",
                ScriptedModel.builder()
                        .respond(r -> r.text("好的").usage(new io.agentscope.core.model.ChatUsage(30, 4, 0.1)))
                        .build());
        RoutedModel model = model(new ModelSpec(PRIMARY, null, null, null));

        StepVerifier.create(stream(model)).expectNextCount(1).verifyComplete();

        int estimate = TokenEstimator.messages(MESSAGES) + MAX_OUTPUT;
        assertThat(model.getContextWindowSize()).as("上下文窗口取模型目录").isEqualTo(WINDOW);
        assertThat(gateway.rateLimitRequests()).containsExactly(estimate);
        assertThat(gateway.reservations()).isEqualTo(1);
        assertThat(gateway.outstanding()).isZero();
        assertThat(gateway.settlements()).singleElement().satisfies(record -> {
            assertThat(record.usage()).isEqualTo(TokenUsage.of(30, 4));
            assertThat(record.source()).isEqualTo(UsageSource.PROVIDER);
            assertThat(record.purpose()).isEqualTo(CallPurpose.REASONING);
            assertThat(record.route().modelId()).isEqualTo(PRIMARY);
            assertThat(record.agentVersionId()).isEqualTo("agv_t");
            assertThat(record.fallback()).isFalse();
            assertThat(record.firstTokenLatency()).isNotNull();
        });
        GenerateOptions sent = providers.callOptions().getFirst();
        assertThat(sent.getStream()).as("一律流式").isTrue();
        assertThat(sent.getMaxTokens()).as("预扣的上限必须发给供应商").isEqualTo(MAX_OUTPUT);
        assertThat(sent.getExecutionConfig().getMaxAttempts()).as("底层客户端只尝试一次").isEqualTo(1);
    }

    @Test
    void callWithoutPlatformContextFails() {
        providers.register(
                "up-primary", ScriptedModel.builder().respond(r -> r.text("x")).build());
        RoutedModel model = model(new ModelSpec(PRIMARY, null, null, null));

        StepVerifier.create(model.stream(MESSAGES, null, null))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(gateway.reservations()).isZero();
    }

    @Test
    void providerErrorsBeforeTheFirstChunkAreRetriedTwiceWithTheSameReservation() {
        ScriptedModel upstream = ScriptedModel.builder()
                .failBeforeFirstChunk(serverError(503))
                .failBeforeFirstChunk(new HttpTransportException("连接被拒绝", new java.net.ConnectException("refused")))
                .respond(r -> r.text("第三次成功").usage(10, 2))
                .build();
        providers.register("up-primary", upstream);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model)).expectNextCount(1).verifyComplete();

        assertThat(upstream.requests()).as("首次加两次重试").hasSize(3);
        assertThat(gateway.rateLimitRequests()).as("每次重试前重新等待限流").hasSize(3);
        assertThat(gateway.reservations()).as("预扣只做一次").isEqualTo(1);
        assertThat(gateway.settlements())
                .singleElement()
                .satisfies(r -> assertThat(r.fallback()).isFalse());
        assertThat(gateway.outstanding()).isZero();
    }

    @Test
    void exhaustedRetriesSwitchToTheFallbackModel() {
        ScriptedModel primary = ScriptedModel.builder()
                .failBeforeFirstChunk(serverError(500))
                .failBeforeFirstChunk(serverError(502))
                .failBeforeFirstChunk(serverError(429))
                .build();
        ScriptedModel fallback = ScriptedModel.builder()
                .respond(r -> r.text("备用模型回答").usage(12, 3))
                .build();
        providers.register("up-primary", primary).register("up-fallback", fallback);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model)).expectNextCount(1).verifyComplete();

        assertThat(primary.requests()).hasSize(3);
        assertThat(fallback.requests()).hasSize(1);
        assertThat(gateway.releases()).as("主模型的预扣没有用量，被释放").isEqualTo(1);
        assertThat(gateway.settlements()).singleElement().satisfies(record -> {
            assertThat(record.fallback()).isTrue();
            assertThat(record.route().modelId()).isEqualTo(FALLBACK);
        });
        assertThat(providers.callOptions().getLast().getMaxTokens())
                .as("备用模型按自己的默认最大输出")
                .isEqualTo(500);
        assertThat(gateway.outstanding()).isZero();
    }

    @Test
    void rejectedRequestsAreNeitherRetriedNorSwitched() {
        ScriptedModel primary =
                ScriptedModel.builder().failBeforeFirstChunk(serverError(401)).build();
        ScriptedModel fallback = ScriptedModel.builder().build();
        providers.register("up-primary", primary).register("up-fallback", fallback);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ProviderRejectedException.class)
                        .hasMessageContaining("HTTP 401"))
                .verify();

        assertThat(primary.requests()).hasSize(1);
        assertThat(fallback.requests()).isEmpty();
        assertThat(gateway.releases()).isEqualTo(1);
    }

    @Test
    void errorsAfterTheFirstChunkAreNotRetriedNorSwitchedAndAreSettledByEstimate() {
        ScriptedModel primary = ScriptedModel.builder()
                .failAfterChunks(r -> r.text("一半的回答"), serverError(503))
                .build();
        ScriptedModel fallback = ScriptedModel.builder().build();
        providers.register("up-primary", primary).register("up-fallback", fallback);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model))
                .expectNextCount(1)
                .expectError(ProviderUnavailableException.class)
                .verify();

        assertThat(primary.requests()).hasSize(1);
        assertThat(fallback.requests()).isEmpty();
        assertThat(gateway.settlements()).singleElement().satisfies(record -> {
            assertThat(record.source()).as("供应商没有报告用量，按估算记账").isEqualTo(UsageSource.ESTIMATED);
            assertThat(record.usage().inputTokens()).isEqualTo(TokenEstimator.messages(MESSAGES));
            assertThat(record.usage().outputTokens()).isEqualTo(TokenEstimator.text("一半的回答"));
        });
    }

    @Test
    void budgetExceededIsNeitherRetriedNorSwitched() {
        ScriptedModel primary = ScriptedModel.builder().build();
        ScriptedModel fallback = ScriptedModel.builder().build();
        providers.register("up-primary", primary).register("up-fallback", fallback);
        gateway.budgetLimit(10L);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model))
                .expectError(BudgetExceededException.class)
                .verify();

        assertThat(primary.requests()).isEmpty();
        assertThat(fallback.requests()).isEmpty();
        assertThat(gateway.rateLimitRequests()).as("只有主模型等过一次限流").hasSize(1);
    }

    @Test
    void rateLimitTimeoutIsNeitherRetriedNorSwitched() {
        ScriptedModel primary = ScriptedModel.builder().build();
        ScriptedModel fallback = ScriptedModel.builder().build();
        providers.register("up-primary", primary).register("up-fallback", fallback);
        gateway.rateLimitTimeout(true);
        RoutedModel model = model(new ModelSpec(PRIMARY, FALLBACK, null, null));

        StepVerifier.create(stream(model))
                .expectError(RateLimitTimeoutException.class)
                .verify();

        assertThat(primary.requests()).isEmpty();
        assertThat(fallback.requests()).isEmpty();
        assertThat(gateway.reservations()).isZero();
    }

    @Test
    void cancellationAfterTheFirstChunkSettlesByEstimateAndLeavesNothingReserved() {
        providers.register("up-primary", new EndlessModel("还在说"));
        RoutedModel model = model(new ModelSpec(PRIMARY, null, null, null));

        StepVerifier.create(stream(model)).expectNextCount(1).thenCancel().verify();

        assertThat(gateway.outstanding()).as("取消时预扣也被结算").isZero();
        assertThat(gateway.settlements()).singleElement().satisfies(record -> {
            assertThat(record.source()).isEqualTo(UsageSource.ESTIMATED);
            assertThat(record.usage().outputTokens()).isEqualTo(TokenEstimator.text("还在说"));
        });
        assertThat(gateway.used())
                .as("计入预算的只剩估算的实际用量，多扣的部分已退回")
                .isEqualTo(TokenEstimator.messages(MESSAGES) + TokenEstimator.text("还在说"));
    }

    @Test
    void callsUnderACompactionWatchAreMeteredAsCompactionAndRecordTheirFailure() {
        providers.register(
                "up-primary",
                ScriptedModel.builder()
                        .respond(r -> r.text("摘要").usage(50, 5))
                        .failBeforeFirstChunk(serverError(400))
                        .build());
        RoutedModel model = model(new ModelSpec(PRIMARY, null, null, null));
        CompactionWatch watch = new CompactionWatch(null, Set.of(), 3);

        StepVerifier.create(stream(model).contextWrite(c -> c.put(CompactionWatch.class, watch)))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(stream(model).contextWrite(c -> c.put(CompactionWatch.class, watch)))
                .expectError(ProviderRejectedException.class)
                .verify();

        assertThat(gateway.settlements()).extracting(ModelCallRecord::purpose).containsExactly(CallPurpose.COMPACTION);
        assertThat(watch.modelFailure()).get().isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void precheckAddsTheMaximumOutputToTheEstimate() {
        RoutedModel model = model(new ModelSpec(PRIMARY, null, 300, null));

        StepVerifier.create(model.precheck(1_234).contextWrite(PlatformContexts.write(PLATFORM)))
                .verifyComplete();

        assertThat(gateway.prechecks()).containsExactly(1_234 + 300);
        assertThat(gateway.reservations()).as("预检不占用预算").isZero();
    }

    @Test
    void backoffDoublesHonoursRetryAfterAndGivesUpBeyondTheInPlaceLimit() {
        RoutedModel.RetryPolicy policy = new RoutedModel.RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(10));

        assertThat(policy.backoff(0, Optional.empty())).contains(Duration.ofMillis(500));
        assertThat(policy.backoff(1, Optional.empty())).contains(Duration.ofSeconds(1));
        assertThat(policy.backoff(0, Optional.of(Duration.ofSeconds(3)))).contains(Duration.ofSeconds(3));
        assertThat(policy.backoff(1, Optional.of(Duration.ofMillis(100)))).contains(Duration.ofSeconds(1));
        assertThat(policy.backoff(0, Optional.of(Duration.ofSeconds(30)))).isEmpty();
    }

    private RoutedModel model(ModelSpec spec) {
        return (RoutedModel) new RoutedModelFactory(gateway, new ProviderClients(List.of(providers)), FAST_RETRY)
                .create("agv_t", spec);
    }

    private static Flux<ChatResponse> stream(Model model) {
        return model.stream(MESSAGES, null, null).contextWrite(PlatformContexts.write(PLATFORM));
    }

    private static HttpTransportException serverError(int status) {
        return new HttpTransportException("供应商返回 " + status, status, "{}");
    }

    /** 发出一个文本块之后不再结束的模型，用来测试取消。 */
    private static final class EndlessModel extends ChatModelBase {

        private final String text;

        EndlessModel(String text) {
            this.text = text;
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.concat(
                    Flux.just(ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(text).build()))
                            .build()),
                    Flux.never());
        }

        @Override
        public String getModelName() {
            return "endless";
        }
    }
}
