package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T17b-A03：每轮结束时产生 {@code session.usage}，本轮合计、按模型的明细与会话累计都与账本（outbox 的 {@code usage} 消息，control-plane
 * 据此入账）一致（ADR-0050）。智能体装配以 {@code ScriptedModel} 为底层供应商的 {@code RoutedModel}，网关是真实的
 * {@code RoutingModelGateway}。
 */
class SessionUsageIT {

    private static final String MODEL_ID = "mdl_usage";
    private static final String UPSTREAM = "up-usage";

    @TempDir
    Path directory;

    private ModelRoutingFixture routing;

    @AfterEach
    void close() {
        GracefulShutdownManager.getInstance().resetForTesting();
        if (routing != null) {
            routing.close();
        }
    }

    @Test
    void everyTurnEndsWithSessionUsageThatMatchesTheLedger() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42"))
                        .usage(ChatUsage.builder()
                                .inputTokens(120)
                                .outputTokens(15)
                                .cachedTokens(80)
                                .build()))
                .respond(r -> r.text("订单 42 已发货")
                        .usage(ChatUsage.builder()
                                .inputTokens(160)
                                .outputTokens(9)
                                .cachedTokens(100)
                                .reasoningTokens(4)
                                .build()))
                .respond(r -> r.text("不客气").usage(30, 2))
                .build();
        WorkerFixture fixture = fixture(upstream);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.send(node, session, "查一下订单 42", Delivery.QUEUE);
        TurnResult first = node.claimAndRun();
        fixture.send(node, session, "谢谢", Delivery.QUEUE);
        TurnResult second = node.claimAndRun();

        List<StoredEvent> events = fixture.events(node, session);
        List<StoredEvent> usages = ofType(events, EventType.SESSION_USAGE);
        assertThat(usages).hasSize(2);
        assertThat(usages).extracting(StoredEvent::turnId).containsExactly(first.turnId(), second.turnId());
        for (StoredEvent usage : usages) {
            int index = events.indexOf(usage);
            assertThat(events.get(index + 1).type())
                    .as("session.usage 紧挨在终态事件之前")
                    .isEqualTo(EventType.SESSION_STATUS_IDLE);
            ContractValidator.validateSchema(usage.data(), "SessionUsageData");
        }

        JsonNode firstUsage = json(usages.get(0).data());
        assertThat(firstUsage.get("model_id").asString()).isEqualTo(MODEL_ID);
        assertThat(usageOf(firstUsage.get("usage")))
                .isEqualTo(ledger(first.turnId()))
                .isEqualTo(List.of(280L, 24L, 180L, 0L, 4L));
        assertThat(firstUsage.get("models")).singleElement().satisfies(model -> {
            assertThat(model.get("model_id").asString()).isEqualTo(MODEL_ID);
            assertThat(model.get("calls").asInt()).as("工具调用前后各一次模型调用").isEqualTo(2);
        });
        assertThat(usageOf(firstUsage.get("session_usage"))).isEqualTo(usageOf(firstUsage.get("usage")));

        JsonNode secondUsage = json(usages.get(1).data());
        assertThat(usageOf(secondUsage.get("usage")))
                .isEqualTo(ledger(second.turnId()))
                .isEqualTo(List.of(30L, 2L, 0L, 0L, 0L));
        assertThat(usageOf(secondUsage.get("session_usage")))
                .as("会话累计等于账本中该会话的合计")
                .isEqualTo(ledgerOfSession(session.sessionId()))
                .isEqualTo(List.of(310L, 26L, 180L, 0L, 4L));
    }

    @Test
    void aTurnWithoutModelCallsReportsZeroAndOmitsTheModel() {
        ScriptedModel upstream = ScriptedModel.builder().build();
        WorkerFixture fixture = fixture(upstream);
        routing.budget("session", "*", 10);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.send(node, session, "你好", Delivery.QUEUE);
        node.claimAndRun();

        StoredEvent usage =
                ofType(fixture.events(node, session), EventType.SESSION_USAGE).getFirst();
        ContractValidator.validateSchema(usage.data(), "SessionUsageData");
        JsonNode data = json(usage.data());
        assertThat(data.has("model_id")).as("预算不足，没有结算的调用").isFalse();
        assertThat(data.get("models").isEmpty()).isTrue();
        assertThat(usageOf(data.get("usage"))).isEqualTo(List.of(0L, 0L, 0L, 0L, 0L));
    }

    private WorkerFixture fixture(ScriptedModel upstream) {
        routing =
                new ModelRoutingFixture(directory, "http://provider.invalid/v1").model(MODEL_ID, UPSTREAM, 32_000, 256);
        RoutedModel routed = routing.routedModel(
                ModelSpec.of(MODEL_ID),
                ModelRoutingFixture.fastRetry(),
                new StaticProviderClientFactory().register(UPSTREAM, upstream));
        return new WorkerFixture(upstream, agent -> agent, routed);
    }

    /** 事件中的用量：输入、输出、缓存读、缓存写、推理。 */
    private static List<Long> usageOf(JsonNode usage) {
        return List.of(
                usage.get("input_tokens").asLong(),
                usage.get("output_tokens").asLong(),
                usage.get("cached_tokens").asLong(),
                usage.get("cache_creation_tokens").asLong(),
                usage.get("reasoning_tokens").asLong());
    }

    /** 账本中一轮的 model_tokens 记录之和（outbox 的 usage 消息，control-plane 的消费者按它入账）。 */
    private static List<Long> ledger(String turnId) {
        return sums("payload ->> 'turn_id' = ?", turnId);
    }

    private static List<Long> ledgerOfSession(String sessionId) {
        return sums("payload ->> 'session_id' = ?", sessionId);
    }

    private static List<Long> sums(String condition, String value) {
        Map<String, Object> row = DbAssertions.row("""
                        SELECT COALESCE(SUM((payload ->> 'input_tokens')::bigint), 0) AS input,
                               COALESCE(SUM((payload ->> 'output_tokens')::bigint), 0) AS output,
                               COALESCE(SUM((payload ->> 'cache_read_tokens')::bigint), 0) AS cache_read,
                               COALESCE(SUM((payload ->> 'cache_write_tokens')::bigint), 0) AS cache_write,
                               COALESCE(SUM((payload ->> 'reasoning_tokens')::bigint), 0) AS reasoning
                          FROM rt.outbox
                         WHERE topic = 'usage' AND payload ->> 'kind' = 'model_tokens'
                        """ + " AND " + condition, value);
        return List.of(
                ((Number) row.get("input")).longValue(),
                ((Number) row.get("output")).longValue(),
                ((Number) row.get("cache_read")).longValue(),
                ((Number) row.get("cache_write")).longValue(),
                ((Number) row.get("reasoning")).longValue());
    }

    private static JsonNode json(String text) {
        return JsonMappers.lenient().readTree(text);
    }
}
