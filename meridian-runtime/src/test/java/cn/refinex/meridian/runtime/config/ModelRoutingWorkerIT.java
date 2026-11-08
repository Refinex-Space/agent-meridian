package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetLevel;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisRateLimiter;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker 执行一轮时的模型层：智能体装配的是以 {@code ScriptedModel} 为底层供应商的 {@code RoutedModel}，网关是真实的
 * {@code RoutingModelGateway}（Redis 限流与四级预算、outbox 用量），见架构设计「模型调用」「计量与预算」。
 *
 * <p>覆盖 T09a-A02（限流超时置回队列）、A03（预算不足以 {@code budget_exceeded} 结束）、A04（压缩器的内部调用同样经过限流、预扣与
 * 计量）、A07（指标有值、{@code span.model_request_start} 带上下文构成且不含正文）。
 */
class ModelRoutingWorkerIT {

    private static final String MODEL_ID = "mdl_test";
    private static final String UPSTREAM = "up-scripted";

    @TempDir
    Path directory;

    private ModelRoutingFixture routing;

    @AfterEach
    void close() {
        if (routing != null) {
            routing.close();
        }
    }

    @Test
    void rateLimitTimeoutEndsTheTurnRetryableAndRequeuesIt() {
        ScriptedModel upstream =
                ScriptedModel.builder().respond(r -> r.text("第一轮").usage(40, 3)).build();
        WorkerFixture fixture = fixture(upstream, 32_000, spec -> spec);
        routing.rateLimit(1, 0);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.send(node, session, "第一条", Delivery.QUEUE);
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        fixture.send(node, session, "第二条", Delivery.QUEUE);
        TurnResult second = node.claimAndRun();

        assertThat(second).isEqualTo(new TurnResult.Requeued(second.turnId(), RequeueReason.RETRYABLE_ERROR));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(ofType(events, EventType.SESSION_ERROR))
                .singleElement()
                .satisfies(error -> assertThat(error.data())
                        .contains("model.rate_limit_timeout")
                        .contains("retryable"));
        assertThat(ofType(events, EventType.SESSION_STATUS_RESCHEDULED)
                        .getLast()
                        .data())
                .contains("retryable_error");
        assertThat(DbAssertions.turn(second.turnId()))
                .containsEntry("status", "queued")
                .containsEntry("recovery_attempt", 1);
        assertThat(upstream.requests()).as("第二轮没有发出模型请求").hasSize(1);
    }

    @Test
    void exhaustedBudgetEndsTheTurnWithBudgetExceeded() {
        ScriptedModel upstream = ScriptedModel.builder().build();
        WorkerFixture fixture = fixture(upstream, 32_000, spec -> spec);
        routing.budget("session", "*", 10);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.send(node, session, "你好", Delivery.QUEUE);
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(result.turnId(), TurnOutcome.COMPLETED, StopReason.BUDGET_EXCEEDED));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(ofType(events, EventType.SESSION_ERROR))
                .singleElement()
                .satisfies(error -> assertThat(error.data())
                        .contains("model.budget_exceeded")
                        .contains("会话预算不足"));
        assertThat(ofType(events, EventType.SESSION_STATUS_IDLE).getLast().data())
                .contains("budget_exceeded");
        assertThat(upstream.requests()).isEmpty();
        assertThat(routing.registry
                        .get("meridian.budget.rejections")
                        .tag("level", "session")
                        .counter()
                        .count())
                .as("meridian_budget_rejections_total{level=session}")
                .isEqualTo(1);
    }

    @Test
    void compactionCallsAreRateLimitedReservedMeteredAndTheRequestStartCarriesTheComposition() {
        String first = "甲".repeat(3_000);
        String second = "乙".repeat(3_000);
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .respond(r -> r.text("## 目标\n两段长文本。").usage(1_250, 12))
                .respond(r -> r.text("答复二").usage(1_400, 3))
                .build();
        WorkerFixture fixture = fixture(upstream, 2_000, spec -> spec.compaction(new CompactionSpec(500)));
        routing.rateLimit(3, 0);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.send(node, session, first, Delivery.QUEUE);
        node.claimAndRun();
        fixture.send(node, session, second, Delivery.QUEUE);
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(result.turnId(), TurnOutcome.COMPLETED, StopReason.END_TURN));
        List<Map<String, Object>> usage = ModelRoutingFixture.usage(session.tenantId());
        assertThat(usage)
                .as("压缩器的内部调用写了用量记录")
                .extracting(record -> record.get("purpose"))
                .containsExactly("reasoning", "compaction", "reasoning");
        long billed = usage.stream()
                .mapToLong(record -> ((Number) record.get("input_tokens")).longValue()
                        + ((Number) record.get("output_tokens")).longValue())
                .sum();
        assertThat(routing.ledger.used(session.tenantId(), BudgetLevel.SESSION, session.sessionId()))
                .as("压缩调用也计入预算，结算后计数与用量记录一致")
                .isEqualTo(billed);
        assertThat(new RedisRateLimiter(RuntimeTestRedis.template())
                        .acquire(
                                RedisRateLimiter.key(routing.accountId, UPSTREAM),
                                new RateLimit(3, 0),
                                1,
                                Instant.now(),
                                false))
                .as("每分钟 3 次的令牌桶已被三次调用（含压缩）用完")
                .isPositive();

        List<StoredEvent> events = fixture.events(node, session);
        assertThat(ofType(events, EventType.AGENT_CONTEXT_COMPACTED))
                .singleElement()
                .satisfies(e -> assertThat(e.data()).contains("\"messages_before\": 3"));
        List<StoredEvent> starts = ofType(events, EventType.SPAN_MODEL_REQUEST_START);
        assertThat(starts)
                .hasSize(2)
                .allSatisfy(start -> assertThat(start.data())
                        .contains("\"context\"")
                        .contains("\"system_prompt\"")
                        .contains("\"history\"")
                        .contains("\"tool_results\"")
                        .doesNotContain("甲甲")
                        .doesNotContain("乙乙")
                        .doesNotContain(fixture.spec.systemPrompt()));
        assertThat(routing.registry
                        .get("meridian.model.tokens")
                        .tag("model", MODEL_ID)
                        .tag("type", "input")
                        .counter()
                        .count())
                .as("meridian_model_tokens_total{type=input}")
                .isEqualTo(1_300 + 1_250 + 1_400);
        assertThat(routing.registry
                        .get("meridian.model.first.token")
                        .tag("model", MODEL_ID)
                        .timer()
                        .count())
                .as("meridian_model_first_token_seconds")
                .isEqualTo(3);
    }

    private WorkerFixture fixture(
            ScriptedModel upstream, int contextWindow, UnaryOperator<TestSpecs.AgentSpecBuilder> agent) {
        routing = new ModelRoutingFixture(directory, "http://provider.invalid/v1")
                .model(MODEL_ID, UPSTREAM, contextWindow, 256);
        RoutedModel routed = routing.routedModel(
                ModelSpec.of(MODEL_ID),
                ModelRoutingFixture.fastRetry(),
                new StaticProviderClientFactory().register(UPSTREAM, upstream));
        return new WorkerFixture(upstream, agent, routed);
    }
}
