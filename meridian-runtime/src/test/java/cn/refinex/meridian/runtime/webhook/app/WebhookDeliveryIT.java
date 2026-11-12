package cn.refinex.meridian.runtime.webhook.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.webhook.domain.RetrySchedule;
import cn.refinex.meridian.runtime.webhook.domain.WebhookSignature;
import cn.refinex.meridian.runtime.webhook.domain.WebhookSubscription;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Webhook 投递（T17a-A01，ADR-0049）：订阅的四种事件按订阅扇出与投递，签名可被验证；接收方返回 500 时按退避重试、次数用尽后失败；
 * 同一事件的每次投递（重试、手动重投）请求体与事件 {@code id} 相同，接收方据此去重；手动重投经公开 API；子线程的 {@code session.error}
 * 不推送（D42）。接收方是 WireMock，订阅由测试替身给出，时钟可控，投递器由测试手动驱动。
 */
@Import(WebhookDeliveryIT.Fixture.class)
class WebhookDeliveryIT extends ApiTestBase {

    @RegisterExtension
    static final WireMockExtension receiver = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** 测试替身的订阅：项目 ID → Webhook。 */
    static final Map<String, List<WebhookSubscription>> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    SessionEventWriter events;

    @Autowired
    SessionService sessions;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void reset() {
        SUBSCRIPTIONS.clear();
        receiver.resetAll();
        // 共用的库中别的测试写入的事件与记录不归本测试的投递器处理
        DbAssertions.update("DELETE FROM rt.webhook_events");
        DbAssertions.update("DELETE FROM rt.webhook_deliveries");
    }

    @Test
    void subscribedEventsAreSignedDeliveredAndRedeliverable() {
        receiver.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(204)));
        String sessionId = createSession(null);
        subscribe(
                new WebhookSubscription(
                        "whk_all",
                        url("/ok"),
                        Set.of("session.status_idle", "session.status_terminated"),
                        true,
                        "whsec_a"),
                new WebhookSubscription(
                        "whk_actions", url("/ok"), Set.of("session.status_requires_action"), true, "whsec_b"),
                new WebhookSubscription("whk_off", url("/ok"), Set.of("session.status_idle"), false, "whsec_c"));

        String idleEventId = append(sessionId, mainThread(sessionId), new SessionEvent.StatusIdle(StopReason.END_TURN));
        api(ApiTestKeys.KEY_A).archiveSession(sessionId, null);
        dispatcher.runOnce();

        List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo("/ok")));
        assertThat(requests).hasSize(2);
        LoggedRequest idle = requests.stream()
                .filter(request -> request.getHeader("Meridian-Event-Type").equals("session.status_idle"))
                .findFirst()
                .orElseThrow();
        JsonNode envelope = JsonMappers.lenient().readTree(idle.getBodyAsString());
        assertThat(envelope.get("id").asString()).isEqualTo(idleEventId);
        assertThat(envelope.get("session_id").asString()).isEqualTo(sessionId);
        assertThat(envelope.get("type").asString()).isEqualTo("session.status_idle");
        assertThat(envelope.get("data").get("stop_reason").asString()).isEqualTo("end_turn");
        assertThat(idle.getHeader("Content-Type")).startsWith("application/json");
        assertThat(idle.getHeader("Meridian-Webhook-Id")).isEqualTo("whk_all");
        assertSignature(idle, "whsec_a");
        assertThat(requests)
                .extracting(request -> request.getHeader("Meridian-Event-Type"))
                .containsExactlyInAnyOrder("session.status_idle", "session.status_terminated");

        // 公开 API：列表、过滤、详情
        ApiResponse list = deliveries("?webhook_id=whk_all&session_id=" + sessionId);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("data")).hasSize(2);
        JsonNode first = list.body().get("data").get(0);
        assertThat(first.get("status").asString()).isEqualTo("succeeded");
        assertThat(first.get("attempts").asInt()).isEqualTo(1);
        assertThat(first.get("last_response_status").asInt()).isEqualTo(204);
        String idleDelivery = idle.getHeader("Meridian-Delivery-Id");
        ApiResponse got = api(ApiTestKeys.KEY_A)
                .call(
                        "GET /v1/webhook-deliveries/{delivery_id}",
                        HttpMethod.GET,
                        "/v1/webhook-deliveries/" + idleDelivery,
                        null,
                        null);
        assertThat(got.text("event_id")).isEqualTo(idleEventId);
        // 其他项目看不到
        assertThat(api(ApiTestKeys.KEY_B)
                        .call(
                                "GET /v1/webhook-deliveries/{delivery_id}",
                                HttpMethod.GET,
                                "/v1/webhook-deliveries/" + idleDelivery,
                                null,
                                null)
                        .status())
                .isEqualTo(404);

        // 手动重投：新记录，请求体与事件 ID 不变，接收方按事件 ID 去重
        ApiResponse redelivered = api(ApiTestKeys.KEY_A)
                .call(
                        "POST /v1/webhook-deliveries/{delivery_id}:redeliver",
                        HttpMethod.POST,
                        "/v1/webhook-deliveries/" + idleDelivery + ":redeliver",
                        null,
                        null);
        assertThat(redelivered.status()).isEqualTo(202);
        assertThat(redelivered.text("status")).isEqualTo("pending");
        assertThat(redelivered.text("redelivery_of")).isEqualTo(idleDelivery);
        dispatcher.runOnce();
        List<LoggedRequest> idleRequests = receiver.findAll(postRequestedFor(urlEqualTo("/ok"))).stream()
                .filter(request -> request.getHeader("Meridian-Event-Type").equals("session.status_idle"))
                .toList();
        assertThat(idleRequests).hasSize(2);
        assertThat(idleRequests.get(1).getBodyAsString())
                .isEqualTo(idleRequests.get(0).getBodyAsString());
        assertThat(idleRequests.get(1).getHeader("Meridian-Delivery-Id")).isEqualTo(redelivered.text("id"));
        assertThat(receivedEventIds(idleRequests)).containsExactly(idleEventId);
        assertSignature(idleRequests.get(1), "whsec_a");
    }

    @Test
    void failedDeliveriesAreRetriedWithBackoffUntilTheyGiveUp() {
        receiver.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        receiver.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        receiver.stubFor(post(urlEqualTo("/down")).willReturn(aResponse().withStatus(500)));
        String sessionId = createSession(null);
        subscribe(
                new WebhookSubscription("whk_flaky", url("/flaky"), Set.of("session.error"), true, "whsec_f"),
                new WebhookSubscription("whk_down", url("/down"), Set.of("session.error"), true, "whsec_d"));

        String errorEventId = append(
                sessionId,
                mainThread(sessionId),
                new SessionEvent.Error("model.unavailable", "down", SessionEvent.RetryStatus.TERMINAL));
        dispatcher.runOnce();

        JsonNode flaky = delivery("whk_flaky", sessionId);
        assertThat(flaky.get("status").asString()).isEqualTo("pending");
        assertThat(flaky.get("attempts").asInt()).isEqualTo(1);
        assertThat(flaky.get("last_response_status").asInt()).isEqualTo(500);
        assertThat(Instant.parse(flaky.get("next_attempt_at").asString()))
                .isEqualTo(
                        Instant.parse(flaky.get("last_attempt_at").asString()).plusSeconds(30));

        // 未到下一次尝试的时间不会重投
        clock.advance(Duration.ofSeconds(20));
        dispatcher.runOnce();
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/flaky")))).hasSize(1);
        clock.advance(Duration.ofSeconds(11));
        dispatcher.runOnce();
        flaky = delivery("whk_flaky", sessionId);
        assertThat(flaky.get("status").asString()).isEqualTo("succeeded");
        assertThat(flaky.get("attempts").asInt()).isEqualTo(2);
        List<LoggedRequest> flakyRequests = receiver.findAll(postRequestedFor(urlEqualTo("/flaky")));
        assertThat(flakyRequests).hasSize(2);
        assertThat(receivedEventIds(flakyRequests)).containsExactly(errorEventId);

        // 一直失败：按计划重试，共 8 次后失败，全部在 24 小时之内
        for (Duration delay : RetrySchedule.DELAYS.subList(1, RetrySchedule.DELAYS.size())) {
            clock.advance(delay.plusSeconds(1));
            dispatcher.runOnce();
        }
        JsonNode down = delivery("whk_down", sessionId);
        assertThat(down.get("status").asString()).isEqualTo("failed");
        assertThat(down.get("attempts").asInt()).isEqualTo(RetrySchedule.MAX_ATTEMPTS);
        assertThat(down.get("next_attempt_at").isNull()).isTrue();
        assertThat(down.get("completed_at").isNull()).isFalse();
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/down")))).hasSize(RetrySchedule.MAX_ATTEMPTS);
        clock.advance(Duration.ofHours(12));
        dispatcher.runOnce();
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/down")))).hasSize(RetrySchedule.MAX_ATTEMPTS);
        assertThat(deliveries("?status=failed&session_id=" + sessionId).body().get("data"))
                .hasSize(1);
    }

    @Test
    void childThreadErrorsAreNotPushedAndDeletedWebhooksFailTheirDeliveries() {
        receiver.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        String sessionId = createSession(null);
        String child = "thr_child_" + sessionId;
        DbAssertions.update("""
                INSERT INTO rt.threads (id, tenant_id, project_id, session_id, parent_thread_id, parent_tool_use_id,
                                        agent_name, status, created_at, updated_at)
                SELECT ?, tenant_id, project_id, session_id, id, 'toolu_child', 'researcher', 'idle', created_at, created_at
                  FROM rt.threads WHERE session_id = ? AND parent_thread_id IS NULL
                """, child, sessionId);
        subscribe(new WebhookSubscription("whk_errors", url("/ok"), Set.of("session.error"), true, "whsec_e"));

        String childError = append(
                sessionId, child, new SessionEvent.Error("tool.failed", "child", SessionEvent.RetryStatus.TERMINAL));
        assertThat(DbAssertions.list(
                        "SELECT event_id FROM rt.webhook_events WHERE session_id = ?", String.class, sessionId))
                .doesNotContain(childError);
        String mainError = append(
                sessionId,
                mainThread(sessionId),
                new SessionEvent.Error("tool.failed", "main", SessionEvent.RetryStatus.TERMINAL));
        // Webhook 在扇出之后、投递之前被删除：投递以失败结束，不发请求
        dispatcher.fanOut();
        SUBSCRIPTIONS.put(ApiTestKeys.PROJECT_A, List.of());
        dispatcher.deliverDue();

        JsonNode delivery = delivery("whk_errors", sessionId);
        assertThat(delivery.get("event_id").asString()).isEqualTo(mainError);
        assertThat(delivery.get("status").asString()).isEqualTo("failed");
        assertThat(delivery.get("attempts").asInt()).isZero();
        assertThat(delivery.get("last_error").asString()).startsWith("webhook_unavailable");
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/ok")))).isEmpty();
    }

    private static void subscribe(WebhookSubscription... webhooks) {
        SUBSCRIPTIONS.put(ApiTestKeys.PROJECT_A, List.of(webhooks));
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + receiver.getPort() + path;
    }

    private String mainThread(String sessionId) {
        return PlatformContexts.call(
                projectA(), () -> sessions.mainThread(sessionId).id());
    }

    private String append(String sessionId, String threadId, EngineEvent event) {
        return events.append(EventBatch.unfenced(sessionId, threadId, List.of(event)))
                        instanceof cn.refinex.meridian.runtime.stream.domain.AppendResult.Appended appended
                ? appended.events().getFirst().id()
                : null;
    }

    private ApiResponse deliveries(String query) {
        return api(ApiTestKeys.KEY_A)
                .call("GET /v1/webhook-deliveries", HttpMethod.GET, "/v1/webhook-deliveries" + query, null, null);
    }

    private JsonNode delivery(String webhookId, String sessionId) {
        JsonNode data = deliveries("?webhook_id=" + webhookId + "&session_id=" + sessionId)
                .body()
                .get("data");
        assertThat(data).hasSize(1);
        return data.get(0);
    }

    private static List<String> receivedEventIds(List<LoggedRequest> requests) {
        return requests.stream()
                .map(request -> JsonMappers.lenient()
                        .readTree(request.getBodyAsString())
                        .get("id")
                        .asString())
                .distinct()
                .toList();
    }

    /** 按接收方的方式验证签名：取时间戳，对 "{t}.{请求体原文}" 计算 HMAC-SHA256 并比较。 */
    private static void assertSignature(LoggedRequest request, String secret) {
        String header = request.getHeader(WebhookSignature.HEADER);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        String timestamp = header.substring(2, header.indexOf(','));
        String v1 = header.substring(header.indexOf("v1=") + 3);
        assertThat(WebhookSignature.hmac(secret, timestamp + "." + request.getBodyAsString()))
                .isEqualTo(v1);
        assertThat(WebhookSignature.hmac("whsec_wrong", timestamp + "." + request.getBodyAsString()))
                .isNotEqualTo(v1);
    }

    /** 可控时钟与订阅替身。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        @Primary
        MutableClock clock() {
            return MutableClock.at(Instant.now());
        }

        @Bean
        @Primary
        WebhookSubscriptionPort testSubscriptions() {
            return (tenantId, projectId) ->
                    tenantId.equals(ApiTestKeys.TENANT) ? SUBSCRIPTIONS.getOrDefault(projectId, List.of()) : List.of();
        }
    }
}
