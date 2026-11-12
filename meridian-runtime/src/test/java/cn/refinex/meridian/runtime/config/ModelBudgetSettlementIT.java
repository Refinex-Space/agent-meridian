package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 预扣与结算成对、Redis 计数与用量记录一致（T09a-A03、A06）：完成、首包之后被取消、首包之前失败三种结束方式，结算后各级计数都等于
 * 用量记录的"输入加输出"之和，没有残留的预扣。网关是真实的 {@code RoutingModelGateway}，底层供应商是脚本。
 */
class ModelBudgetSettlementIT {

    private static final String MODEL_ID = "mdl_settle";
    private static final List<Msg> MESSAGES = List.of(Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("请回答").build())
            .build());

    @TempDir
    Path directory;

    private ModelRoutingFixture routing;

    @BeforeEach
    void createRouting() {
        routing = new ModelRoutingFixture(directory, "http://provider.invalid/v1").model(MODEL_ID, "up", 32_000, 500);
    }

    @AfterEach
    void close() {
        routing.close();
    }

    @Test
    void completedCancelledAndFailedCallsLeaveCountersEqualToTheUsageRecords() throws Exception {
        String sessionId = "ses_" + Ids.newUlid();
        PlatformContext context = routing.context(sessionId);
        BlockingAfterFirstChunk endless = new BlockingAfterFirstChunk("还在生成中");
        StaticProviderClientFactory providers = new StaticProviderClientFactory();
        ScriptedModel scripted = ScriptedModel.builder()
                .respond(r -> r.text("完整回答")
                        .usage(io.agentscope.core.model.ChatUsage.builder()
                                .inputTokens(30)
                                .outputTokens(9)
                                .cachedTokens(12)
                                .cacheCreationTokens(4)
                                .reasoningTokens(2)
                                .build()))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 401", 401, "{}"))
                .build();
        providers.register("up", new Switching(scripted, endless));
        RoutedModel model = routing.routedModel(ModelSpec.of(MODEL_ID), ModelRoutingFixture.fastRetry(), providers);

        // 1. 完成：按供应商报告的用量结算
        model.stream(MESSAGES, null, null)
                .contextWrite(PlatformContexts.write(context))
                .blockLast(Duration.ofSeconds(10));
        // 2. 首包之后被取消：按估算结算，退回预扣中没有用到的部分
        Disposable cancelled = model.stream(MESSAGES, null, null)
                .contextWrite(PlatformContexts.write(context))
                .subscribe();
        assertThat(endless.firstChunkSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.SESSION, sessionId))
                .as("取消之前，预扣（估算输入加最大输出 500）还在计数里")
                .isGreaterThan(500);
        cancelled.dispose();
        // 3. 首包之前失败：只释放预扣，不写用量记录
        assertThatThrownBy(() -> model.stream(MESSAGES, null, null)
                        .contextWrite(PlatformContexts.write(context))
                        .blockLast(Duration.ofSeconds(10)))
                .isInstanceOf(ProviderRejectedException.class);

        List<Map<String, Object>> usage = awaitUsage(2);
        assertThat(usage).extracting(record -> record.get("usage_source")).containsExactly("provider", "estimated");
        assertThat(usage.getFirst())
                .as("五类 token 分别记")
                .containsEntry("input_tokens", 30)
                .containsEntry("output_tokens", 9)
                .containsEntry("cache_read_tokens", 12)
                .containsEntry("cache_write_tokens", 4)
                .containsEntry("reasoning_tokens", 2)
                .containsEntry("kind", "model_tokens")
                .containsEntry("purpose", "reasoning")
                .containsEntry("model_id", MODEL_ID)
                .containsEntry("agent_version_id", "agv_fixture")
                .containsEntry("tenant_id", routing.tenantId)
                .containsEntry("project_id", routing.projectId)
                .containsEntry("session_id", sessionId)
                .containsKeys("request_id", "occurred_at", "thread_id", "turn_id", "provider_account_id");
        long billed = usage.stream()
                .mapToLong(record -> ((Number) record.get("input_tokens")).longValue()
                        + ((Number) record.get("output_tokens")).longValue())
                .sum();
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.TENANT, routing.tenantId))
                .isEqualTo(billed);
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.PROJECT, routing.projectId))
                .isEqualTo(billed);
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.API_KEY, "key_fixture"))
                .isEqualTo(billed);
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.SESSION, sessionId))
                .isEqualTo(billed);
        assertThat(RuntimeTestRedis.template().keys("budget:{" + routing.tenantId + "}:rsv:*"))
                .as("没有残留的预扣记录")
                .isEmpty();
    }

    private List<Map<String, Object>> awaitUsage(int count) throws InterruptedException {
        // 取消时的结算在取消信号之后异步完成
        for (int i = 0; i < 50; i++) {
            List<Map<String, Object>> usage = routing.usage();
            if (usage.size() >= count
                    && RuntimeTestRedis.template()
                            .keys("budget:{" + routing.tenantId + "}:rsv:*")
                            .isEmpty()) {
                return usage;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return routing.usage();
    }

    /** 第一次调用交给脚本，第二次交给不会结束的模型，第三次再交给脚本。 */
    private static final class Switching extends ChatModelBase {

        private final ScriptedModel scripted;
        private final BlockingAfterFirstChunk endless;
        private int calls;

        Switching(ScriptedModel scripted, BlockingAfterFirstChunk endless) {
            this.scripted = scripted;
            this.endless = endless;
        }

        @Override
        protected synchronized Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            return calls == 2 ? endless.stream(messages, tools, options) : scripted.stream(messages, tools, options);
        }

        @Override
        public String getModelName() {
            return "switching";
        }
    }

    /** 发出一个文本块之后不再结束的模型。 */
    private static final class BlockingAfterFirstChunk extends ChatModelBase {

        private final String text;
        private final CountDownLatch firstChunkSent = new CountDownLatch(1);

        BlockingAfterFirstChunk(String text) {
            this.text = text;
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.concat(
                    Flux.just(ChatResponse.builder()
                                    .content(List.of(
                                            TextBlock.builder().text(text).build()))
                                    .build())
                            .doOnComplete(firstChunkSent::countDown),
                    Flux.never());
        }

        @Override
        public String getModelName() {
            return "endless";
        }
    }
}
