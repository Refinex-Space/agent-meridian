package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 预算限额来自 control-plane 同步到 Redis 的哈希（T16-A03、A08 的 runtime 侧，ADR-0048）：哈希的写法与 control-plane 的
 * {@code BudgetService} 相同（{@code common.budget.BudgetKeys}）。新租户没有任何限额时模型调用以 {@code budget_exceeded} 被拒绝，设置后可用；
 * 四级中任一级达到硬阈值都拒绝；会话中的调用计入创建会话的 API Key。
 */
class BudgetLimitsWorkerIT {

    private static final String MODEL_ID = "mdl_budget";
    private static final String UPSTREAM = "up-budget";
    private static final String API_KEY = "key_budget";

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
    void aTenantWithoutBudgetsIsRejectedUntilAnAdministratorSetsThem() {
        ScriptedModel upstream =
                ScriptedModel.builder().respond(r -> r.text("好的").usage(40, 3)).build();
        WorkerFixture fixture = fixture(upstream);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture);

        fixture.send(node, session, "你好", Delivery.QUEUE);
        TurnResult rejected = node.claimAndRun();

        assertThat(rejected)
                .isEqualTo(
                        new TurnResult.Finished(rejected.turnId(), TurnOutcome.COMPLETED, StopReason.BUDGET_EXCEEDED));
        assertThat(ofType(fixture.events(node, session), EventType.SESSION_ERROR))
                .singleElement()
                .satisfies(error -> assertThat(error.data())
                        .contains("model.budget_exceeded")
                        .contains("租户预算不足"));
        assertThat(upstream.requests()).as("没有发出模型请求").isEmpty();

        // 管理员设置了四级预算（不限也要显式设置）：control-plane 写进限额哈希
        limits(session, Map.of("tenant", "-1", "project:*", "-1", "api_key:*", "-1", "session:*", "-1"));
        fixture.send(node, session, "再试一次", Delivery.QUEUE);
        TurnResult accepted = node.claimAndRun();

        assertThat(accepted)
                .isEqualTo(new TurnResult.Finished(accepted.turnId(), TurnOutcome.COMPLETED, StopReason.END_TURN));
        assertThat(upstream.requests()).hasSize(1);
        assertThat(ModelRoutingFixture.usage(session.tenantId()))
                .singleElement()
                .satisfies(usage -> assertThat(usage).containsEntry("api_key_id", API_KEY));
        assertThat(routing.ledger.used(session.tenantId(), BudgetLevel.API_KEY, API_KEY))
                .as("会话中的调用计入创建会话的 API Key")
                .isEqualTo(43);
    }

    @ParameterizedTest
    @EnumSource(BudgetLevel.class)
    void reachingAnyLevelsHardLimitStopsTheTurn(BudgetLevel level) {
        ScriptedModel upstream = ScriptedModel.builder().build();
        WorkerFixture fixture = fixture(upstream);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture);
        Map<String, String> limits =
                new LinkedHashMap<>(Map.of("tenant", "-1", "project:*", "-1", "api_key:*", "-1", "session:*", "-1"));
        String scope = switch (level) {
            case TENANT -> session.tenantId();
            case PROJECT -> session.projectId();
            case API_KEY -> API_KEY;
            case SESSION -> session.sessionId();
        };
        // 具体范围的限额覆盖该级的默认值
        limits.put(BudgetKeys.limitField(level, scope), "10");
        limits(session, limits);

        fixture.send(node, session, "你好", Delivery.QUEUE);
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(result.turnId(), TurnOutcome.COMPLETED, StopReason.BUDGET_EXCEEDED));
        String label = switch (level) {
            case TENANT -> "租户";
            case PROJECT -> "项目";
            case API_KEY -> "API Key";
            case SESSION -> "会话";
        };
        assertThat(ofType(fixture.events(node, session), EventType.SESSION_ERROR))
                .singleElement()
                .satisfies(error -> assertThat(error.data()).contains(label + "预算不足"));
        assertThat(upstream.requests()).isEmpty();
    }

    private WorkerFixture fixture(ScriptedModel upstream) {
        routing = new ModelRoutingFixture(directory, "http://provider.invalid/v1")
                .model(MODEL_ID, UPSTREAM, 32_000, 256)
                .limitsFromRedis();
        RoutedModel routed = routing.routedModel(
                ModelSpec.of(MODEL_ID),
                ModelRoutingFixture.fastRetry(),
                new StaticProviderClientFactory().register(UPSTREAM, upstream));
        return new WorkerFixture(upstream, spec -> spec, routed);
    }

    /** 由 API Key 创建的会话：创建时 runtime 记下 Key（这里直接写库，等同于经 API 创建）。 */
    private static TestSession session(WorkerFixture fixture) {
        TestSession session = fixture.session();
        JdbcClient.create(RuntimeTestDatabase.dataSource())
                .sql("UPDATE rt.sessions SET api_key_id = :key WHERE id = :id")
                .param("key", API_KEY)
                .param("id", session.sessionId())
                .update();
        return session;
    }

    private static void limits(TestSession session, Map<String, String> fields) {
        RuntimeTestRedis.template().opsForHash().putAll(BudgetKeys.limits(session.tenantId()), fields);
    }
}
