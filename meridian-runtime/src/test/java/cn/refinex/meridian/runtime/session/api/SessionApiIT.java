package cn.refinex.meridian.runtime.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * 会话与事件接口的行为：创建与固化版本、分页、更新、归档与删除、发送事件的投递，以及幂等（T07-A05）、跨项目隔离（T07-A06）、
 * problem+json 与非法的事件状态组合（T07-A07）。每次交互都按规范校验（{@code ContractValidator}）。
 */
class SessionApiIT extends ApiTestBase {

    @Autowired
    private TurnEnqueuer enqueuer;

    @Autowired
    private PendingActionRepository pendingActions;

    @Autowired
    private Clock clock;

    @Test
    void createPinsTheAgentVersionAndEnvironmentRevision() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        ApiResponse byVersion = api.createSession("""
                {"agent": "agv_dev_assistant_1", "environment": "env_dev_none", "interaction": "unattended",
                 "custom_tools": [{"name": "lookup_order", "description": "查询订单",
                                   "input_schema": {"type": "object"}}]}
                """, null);

        assertThat(byVersion.status()).isEqualTo(201);
        assertThat(byVersion.text("agent_version_id")).isEqualTo("agv_dev_assistant_1");
        assertThat(byVersion.text("interaction")).isEqualTo("unattended");
        assertThat(byVersion.body().get("end_user_id").isNull()).isTrue();
        assertThat(byVersion
                        .body()
                        .get("custom_tools")
                        .get(0)
                        .get("side_effect")
                        .asString())
                .isEqualTo("non_idempotent");
        assertThat(byVersion.body().get("last_seq").asLong()).isZero();
        assertThat(DbAssertions.row(
                        "SELECT agent_version_id, environment_revision, api_key_id FROM rt.sessions WHERE id = ?",
                        byVersion.text("id")))
                .containsEntry("agent_version_id", "agv_dev_assistant_1")
                .containsEntry("environment_revision", 1L)
                .as("会话记下创建它的 API Key，轮次的预算与用量计入它（ADR-0048）")
                .containsEntry("api_key_id", "key_test_a");
        assertThat(api.listThreads(byVersion.text("id"), null).body().get("data"))
                .singleElement()
                .satisfies(thread ->
                        assertThat(thread.get("parent_thread_id").isNull()).isTrue());

        ApiResponse missingAgent =
                api.createSession("{\"agent\": \"agt_nope\", \"environment\": \"env_dev_none\"}", null);
        assertProblem(missingAgent, 404, "agent.not_found");
        ApiResponse missingEnvironment =
                api.createSession("{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_nope\"}", null);
        assertProblem(missingEnvironment, 404, "environment.not_found");
    }

    @Test
    void listPagesNewestFirstAndUpdateReplacesMetadata() {
        String endUser = "u_" + Ids.newUlid();
        String first = createSession(endUser);
        String second = createSession(endUser);
        String third = createSession(endUser);
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        ApiResponse page1 = api.listSessions("limit=2&end_user_id=" + endUser);
        assertThat(ids(page1.body().get("data"))).containsExactly(third, second);
        assertThat(page1.body().get("has_more").asBoolean()).isTrue();
        ApiResponse page2 = api.listSessions("limit=2&end_user_id=" + endUser + "&after=" + page1.text("next_cursor"));
        assertThat(ids(page2.body().get("data"))).containsExactly(first);
        assertThat(page2.body().get("has_more").asBoolean()).isFalse();
        assertThat(page2.body().get("next_cursor").isNull()).isTrue();

        ApiResponse updated = api.updateSession(first, "{\"metadata\": {\"ticket\": \"INC-9\"}}");
        assertThat(updated.body().get("metadata").get("ticket").asString()).isEqualTo("INC-9");
        ApiResponse unchanged = api.updateSession(first, "{}");
        assertThat(unchanged.body().get("metadata").get("ticket").asString()).isEqualTo("INC-9");
        ApiResponse cleared = api.updateSession(first, "{\"metadata\": {}}");
        assertThat(cleared.body().get("metadata").isEmpty()).isTrue();
    }

    @Test
    void messageToAnIdleSessionStartsATurnAndLaterMessagesGoToTheInbox() {
        String sessionId = createSession(null);
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        ApiResponse first = api.sendMessage(sessionId, "第一条");
        assertThat(first.status()).isEqualTo(202);
        JsonNode event = first.body().get("data").get(0);
        assertThat(event.get("type").asString()).isEqualTo("user.message");
        assertThat(event.get("turn_id").isNull()).isTrue();
        assertThat(event.get("data").get("delivery").asString()).isEqualTo("queue");
        Map<String, Object> turn = DbAssertions.row(
                "SELECT t.status, t.kind, i.payload::text AS payload FROM rt.turns t"
                        + " JOIN rt.turn_inputs i ON i.turn_id = t.id WHERE t.session_id = ?",
                sessionId);
        assertThat(turn).containsEntry("status", "queued").containsEntry("kind", "messages");
        assertThat((String) turn.get("payload")).contains(event.get("id").asString());

        ApiResponse batch = api.sendEvents(sessionId, """
                {"events": [
                  {"type": "user.message", "delivery": "steer", "content": [{"type": "text", "text": "插一句"}]},
                  {"type": "user.interrupt"},
                  {"type": "user.message", "delivery": "steer", "content": [{"type": "text", "text": "中断后的"}]}
                ]}
                """, null);
        assertThat(batch.status()).isEqualTo(202);
        assertThat(batch.body().get("data"))
                .extracting(e -> e.get("seq").asLong())
                .containsExactly(2L, 3L, 4L);
        // T08：排队中的消息轮被中断时取消，本批的两条消息（中断之后的按 queue）合成下一轮
        assertThat(DbAssertions.list(
                        "SELECT status FROM rt.turns WHERE session_id = ? ORDER BY created_at, id",
                        String.class,
                        sessionId))
                .containsExactly("cancelled", "queued");
        assertThat(DbAssertions.list(
                        "SELECT i.payload::text FROM rt.turns t JOIN rt.turn_inputs i ON i.turn_id = t.id "
                                + "WHERE t.session_id = ? AND t.status = 'queued'",
                        String.class,
                        sessionId))
                .singleElement()
                .satisfies(payload -> assertThat(payload).containsSubsequence("插一句", "中断后的"));
        assertThat(DbAssertions.list(
                        "SELECT delivery FROM rt.inbox_messages WHERE session_id = ?", String.class, sessionId))
                .as("线程空闲后直接入队，不经收件箱")
                .isEmpty();
    }

    @Test
    void sameIdempotencyKeyReplaysTheFirstResultAndADifferentBodyConflicts() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String key = "idem-" + Ids.newUlid();
        String body = "{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_dev_none\", \"end_user_id\": \"u_1\"}";

        ApiResponse first = api.createSession(body, key);
        ApiResponse replay = api.createSession(
                "{ \"end_user_id\" : \"u_1\",  \"environment\": \"env_dev_none\", \"agent\": \"agt_dev_assistant\" }",
                key);
        ApiResponse conflict = api.createSession(body.replace("u_1", "u_2"), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.headers().containsHeader("Idempotent-Replayed")).isFalse();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(first.body());
        assertProblem(conflict, 409, "idempotency.key_reused");
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.sessions WHERE project_id = ? AND end_user_id = 'u_1' AND id = ?",
                        Long.class,
                        ApiTestKeys.PROJECT_A,
                        first.text("id")))
                .containsExactly(1L);

        String sessionId = first.text("id");
        String eventsKey = "idem-" + Ids.newUlid();
        String events = "{\"events\":[{\"type\":\"user.message\",\"content\":[{\"type\":\"text\",\"text\":\"一次\"}]}]}";
        ApiResponse sent = api.sendEvents(sessionId, events, eventsKey);
        ApiResponse resent = api.sendEvents(sessionId, events, eventsKey);
        assertThat(resent.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(resent.body()).isEqualTo(sent.body());
        assertThat(api.listEvents(sessionId, null).body().get("data")).hasSize(1);

        ApiResponse otherKeyHolder = api(ApiTestKeys.KEY_B).createSession(body, key);
        assertThat(otherKeyHolder.status()).as("幂等键按 API Key 隔离").isEqualTo(201);
        assertThat(otherKeyHolder.text("id")).isNotEqualTo(first.text("id"));
    }

    @Test
    void anotherProjectCannotReadOrWriteTheSessionEvenWithTheSameEndUser() {
        String endUser = "u_shared_" + Ids.newUlid();
        String sessionA = createSession(endUser);
        ApiTestClient b = api(ApiTestKeys.KEY_B);
        String sessionB = b.createSession(
                        "{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_dev_none\", \"end_user_id\": \""
                                + endUser + "\"}",
                        null)
                .text("id");

        assertProblem(b.getSession(sessionA), 404, "session.not_found");
        assertProblem(b.updateSession(sessionA, "{\"metadata\": {}}"), 404, "session.not_found");
        assertProblem(b.sendMessage(sessionA, "越权"), 404, "session.not_found");
        assertProblem(b.listEvents(sessionA, null), 404, "session.not_found");
        assertProblem(b.listThreads(sessionA, null), 404, "session.not_found");
        assertProblem(b.listPendingActions(sessionA, null), 404, "session.not_found");
        assertProblem(b.archiveSession(sessionA, null), 404, "session.not_found");
        assertProblem(b.deleteSession(sessionA), 404, "session.not_found");
        assertProblem(
                b.call(
                        "GET /v1/sessions/{session_id}/stream",
                        HttpMethod.GET,
                        "/v1/sessions/" + sessionA + "/stream",
                        null,
                        null),
                404,
                "session.not_found");
        assertThat(ids(b.listSessions("end_user_id=" + endUser).body().get("data")))
                .containsExactly(sessionB);
        assertThat(ids(api(ApiTestKeys.KEY_A)
                        .listSessions("end_user_id=" + endUser)
                        .body()
                        .get("data")))
                .containsExactly(sessionA);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.session_events WHERE session_id = ?", Long.class, sessionA))
                .containsExactly(0L);
        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionA).text("status")).isEqualTo("idle");
    }

    @Test
    void errorsAreProblemJsonWithCodeAndRequestId() {
        ApiResponse anonymous = api(null)
                .callInvalid(
                        "GET /v1/sessions",
                        HttpMethod.GET,
                        "/v1/sessions",
                        null,
                        Map.of("X-Request-Id", "req-t07-a07"));
        assertProblem(anonymous, 401, "auth.unauthenticated");
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        ApiResponse traced = api(ApiTestKeys.KEY_A)
                .callInvalid(
                        "GET /v1/sessions", HttpMethod.GET, "/v1/sessions", null, Map.of("traceparent", traceparent));
        assertThat(traced.status()).isEqualTo(200);
        assertThat(traced.headers().getFirst("traceparent"))
                .as("合法的 traceparent 原样返回")
                .isEqualTo(traceparent);
        assertThat(anonymous.text("request_id")).isEqualTo("req-t07-a07");
        assertThat(anonymous.headers().getFirst("X-Request-Id")).isEqualTo("req-t07-a07");
        assertThat(anonymous.headers().getContentType()).hasToString("application/problem+json");

        ApiResponse badKey =
                api("mk_not_a_key").callInvalid("GET /v1/sessions", HttpMethod.GET, "/v1/sessions", null, Map.of());
        assertProblem(badKey, 401, "auth.invalid_api_key");

        ApiResponse readOnly = api(ApiTestKeys.KEY_A_READ_ONLY)
                .createSession("{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_dev_none\"}", null);
        assertProblem(readOnly, 403, "auth.insufficient_scope");

        assertProblem(api(ApiTestKeys.KEY_A).getSession(Ids.newId(IdPrefix.SESSION)), 404, "session.not_found");

        ApiResponse missingField = api(ApiTestKeys.KEY_A)
                .callInvalid(
                        "POST /v1/sessions",
                        HttpMethod.POST,
                        "/v1/sessions",
                        "{\"environment\": \"env_dev_none\"}",
                        Map.of());
        assertProblem(missingField, 400, "request.validation_failed");
        assertThat(missingField.body().get("errors").get(0).get("field").asString())
                .isEqualTo("agent");

        ApiResponse unknownField = api(ApiTestKeys.KEY_A)
                .callInvalid(
                        "POST /v1/sessions",
                        HttpMethod.POST,
                        "/v1/sessions",
                        "{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_dev_none\", \"surprise\": 1}",
                        Map.of());
        assertProblem(unknownField, 400, "request.invalid");

        ApiResponse badLimit = api(ApiTestKeys.KEY_A)
                .callInvalid("GET /v1/sessions", HttpMethod.GET, "/v1/sessions?limit=0", null, Map.of());
        assertProblem(badLimit, 400, "request.validation_failed");
        assertThat(badLimit.body().get("errors").get(0).get("field").asString()).isEqualTo("limit");

        ApiResponse badCursor = api(ApiTestKeys.KEY_A).listSessions("after=not-a-cursor");
        assertProblem(badCursor, 400, "request.invalid_cursor");
    }

    @Test
    void illegalEventAndStateCombinationsAreRejectedWithSpecificCodes() {
        String sessionId = createSession(null);
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String confirmation = """
                {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true}]}""";

        assertProblem(
                api.sendEvents(sessionId, confirmation.formatted(Ids.newId(IdPrefix.PENDING_ACTION)), null),
                409,
                "event.action_not_found");

        String customAction = pendingAction(sessionId, PendingAction.Type.CUSTOM_TOOL_RESULT);
        assertProblem(
                api.sendEvents(sessionId, confirmation.formatted(customAction), null),
                409,
                "event.action_type_mismatch");
        ApiResponse result = api.sendEvents(sessionId, """
                {"events": [{"type": "user.custom_tool_result", "action_id": "%s",
                             "content": [{"type": "text", "text": "订单已发货"}]}]}""".formatted(customAction), null);
        assertThat(result.status()).isEqualTo(202);
        assertThat(result.body().get("data").get(0).get("data").get("error").asBoolean())
                .isFalse();
        // T08：回应后动作标为已回应，不再出现在等待列表中，同一动作不能再次回应
        assertThat(api.listPendingActions(sessionId, null).body().get("data")).isEmpty();
        assertProblem(
                api.sendEvents(
                        sessionId,
                        "{\"events\": [{\"type\": \"user.custom_tool_result\", \"action_id\": \"" + customAction
                                + "\"}]}",
                        null),
                409,
                "event.action_not_pending");
        assertThat(api.listPendingActions(sessionId, "status=resolved").body().get("data"))
                .hasSize(1);

        ApiResponse duplicate = api.sendEvents(sessionId, """
                {"events": [{"type": "user.recovery_confirmation", "action_id": "act_x", "decision": "skip"},
                            {"type": "user.recovery_confirmation", "action_id": "act_x", "decision": "retry"}]}""", null);
        assertProblem(duplicate, 400, "request.validation_failed");

        ApiResponse badImage = api.callInvalid(
                "POST /v1/sessions/{session_id}/events",
                HttpMethod.POST,
                "/v1/sessions/" + sessionId + "/events",
                "{\"events\":[{\"type\":\"user.message\",\"content\":[{\"type\":\"image\"}]}]}",
                Map.of());
        assertProblem(badImage, 400, "request.validation_failed");
        assertThat(badImage.body().get("errors").get(0).get("field").asString()).isEqualTo("events[0].content[0]");

        // D52：本机路径与其他协议的图片地址在入口拒绝，不会交给模型转换器读本机文件
        for (String url :
                new String[] {"file:///etc/passwd", "/etc/passwd", "jar:file:/x.jar!/a.png", "http:///a.png"}) {
            ApiResponse localImage = api.sendEvents(
                    sessionId,
                    "{\"events\":[{\"type\":\"user.message\",\"content\":[{\"type\":\"image\",\"url\":\"" + url
                            + "\"}]}]}",
                    null);
            assertProblem(localImage, 400, "request.validation_failed");
            assertThat(localImage.body().get("errors").get(0).get("field").asString())
                    .isEqualTo("events[0].content[0].url");
        }
    }

    @Test
    void archiveAndDeleteFollowTheStateMachine() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String archivedId = createSession(null);
        String action = pendingAction(archivedId, PendingAction.Type.TOOL_CONFIRMATION);

        ApiResponse archived = api.archiveSession(archivedId, null);
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.text("status")).isEqualTo("terminated");
        assertThat(archived.body().get("terminated_at").isNull()).isFalse();
        assertThat(archived.body().get("deleted_at").isNull()).isTrue();
        assertThat(DbAssertions.list("SELECT status FROM rt.pending_actions WHERE id = ?", String.class, action))
                .containsExactly("expired");
        assertThat(DbAssertions.list("SELECT status FROM rt.turns WHERE session_id = ?", String.class, archivedId))
                .containsExactly("cancelled");
        assertThat(api.listEvents(archivedId, null).body().get("data")).last().satisfies(e -> {
            assertThat(e.get("type").asString()).isEqualTo("session.status_terminated");
            assertThat(e.get("data").get("reason").asString()).isEqualTo("archived");
        });
        assertThat(api.archiveSession(archivedId, null).body()).isEqualTo(archived.body());
        assertProblem(api.sendMessage(archivedId, "还在吗"), 409, "session.terminated");
        assertProblem(api.updateSession(archivedId, "{\"metadata\": {}}"), 409, "session.terminated");

        String runningId = createSession(null);
        api.sendMessage(runningId, "开始");
        String turnId = DbAssertions.list("SELECT id FROM rt.turns WHERE session_id = ?", String.class, runningId)
                .getFirst();
        markRunningOnAnotherNode(turnId);
        assertProblem(api.archiveSession(runningId, null), 409, "session.busy");

        ApiResponse deleted = api.deleteSession(runningId);
        assertThat(deleted.status()).isEqualTo(202);
        assertThat(deleted.text("status")).isEqualTo("terminated");
        assertThat(deleted.body().get("deleted_at").isNull()).isFalse();
        assertThat(DbAssertions.turn(turnId)).containsEntry("status", "cancelled");
        assertThat(DbAssertions.running(ApiTestKeys.TENANT))
                .as("执行中的轮次被取消时释放并发名额")
                .isZero();
        assertThat(api.deleteSession(runningId).body()).isEqualTo(deleted.body());
        assertThat(api.getSession(runningId).body().get("deleted_at").isNull()).isFalse();
        assertThat(ids(api.listSessions("limit=100").body().get("data"))).doesNotContain(runningId);
        assertProblem(api.archiveSession(runningId, null), 409, "session.terminated");
        assertThat(api.deleteSession(archivedId).status()).isEqualTo(202);
    }

    private String pendingAction(String sessionId, PendingAction.Type type) {
        String threadId = api(ApiTestKeys.KEY_A)
                .listThreads(sessionId, null)
                .body()
                .get("data")
                .get(0)
                .get("id")
                .asString();
        String turnId = Ids.newId(IdPrefix.TURN);
        String actionId = Ids.newId(IdPrefix.PENDING_ACTION);
        PlatformContexts.run(projectA(), () -> {
            enqueuer.enqueue(
                    turnId,
                    NewTurn.of(
                            ApiTestKeys.TENANT,
                            ApiTestKeys.PROJECT_A,
                            sessionId,
                            threadId,
                            new TurnInput.NewMessages(
                                    List.of(new InboundMessage(
                                            Ids.newId(IdPrefix.EVENT), List.of(new MessageContent.Text("你好")))),
                                    null)));
            DbAssertions.update("UPDATE rt.turns SET status = 'awaiting_action' WHERE id = ?", turnId);
            pendingActions.insert(
                    sessionId,
                    turnId,
                    threadId,
                    new PendingAction(
                            actionId,
                            type,
                            "toolu_1",
                            "write_file",
                            Map.of("path", "a.md"),
                            clock.instant().plus(Duration.ofMinutes(30))),
                    Map.of(),
                    clock.instant());
        });
        return actionId;
    }

    /** 模拟另一个节点认领并执行这一轮：轮次进入 running，租户并发计数加一。 */
    private static void markRunningOnAnotherNode(String turnId) {
        DbAssertions.update(
                "UPDATE rt.turns SET status = 'running', lease_owner = 'node-other', lease_epoch = 1,"
                        + " lease_expires_at = now() + interval '30 seconds' WHERE id = ?",
                turnId);
        DbAssertions.update(
                "UPDATE rt.tenant_concurrency SET running = running + 1 WHERE tenant_id = ?", ApiTestKeys.TENANT);
    }

    private static List<String> ids(JsonNode data) {
        return data.valueStream().map(node -> node.get("id").asString()).toList();
    }

    private static void assertProblem(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(status);
        assertThat(response.headers().getContentType()).hasToString("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
        assertThat(response.text("type")).isEqualTo("urn:meridian:error:" + code);
        assertThat(response.text("request_id")).isEqualTo(response.headers().getFirst("X-Request-Id"));
        assertThat(response.headers().getFirst(HttpHeaders.CONTENT_TYPE)).isNotNull();
    }
}
