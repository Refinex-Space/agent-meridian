package cn.refinex.meridian.runtime.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.ConclusionOrigin;
import cn.refinex.meridian.runtime.session.app.TurnStatsRollup;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * 控制台需要的查询（T19b-A03）：项目级待处理动作的列表与历史、会话运行概况、会话列表的筛选与底层数据一致。轮次与动作经 API 产生，
 * 结束状态、错误事件与结论直接写库模拟 Worker（{@code worker.enabled=false}）；每个断言的期望值用 SQL 从同一份数据算出。
 */
class ConsoleQueriesIT extends ApiTestBase {

    @Autowired
    private TurnStatsRollup rollup;

    @Test
    void pendingActionsAndStatsMatchTheUnderlyingRows() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        Instant now = Instant.now();
        Instant finished = now.minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);

        // 三个会话各发一条消息，得到排队的轮次
        String failed = createSession("u_console_1");
        String completed = createSession("u_console_2");
        String waiting = createSession("u_console_3");
        String deleted = createSession("u_console_4");
        for (String session : List.of(failed, completed, waiting, deleted)) {
            assertThat(api.sendMessage(session, "你好").status()).isEqualTo(202);
        }
        String failedTurn = turnOf(failed);
        String completedTurn = turnOf(completed);
        String waitingTurn = turnOf(waiting);
        String deletedTurn = turnOf(deleted);

        // 模拟 Worker：一轮失败（带 session.error），一轮完成，一轮等待回应；被删除的会话里也有一个动作
        finish(failedTurn, "failed", finished);
        DbAssertions.update("UPDATE rt.sessions SET status = 'idle' WHERE id = ?", failed);
        appendError(failed, failedTurn, "model.rate_limit_timeout", "模型限流等待超时");
        finish(completedTurn, "completed", finished);
        DbAssertions.update("UPDATE rt.sessions SET status = 'running' WHERE id = ?", completed);
        DbAssertions.update(
                "UPDATE rt.turns SET status = 'awaiting_action', finished_at = ? WHERE id = ?",
                Timestamp.from(finished),
                waitingTurn);
        DbAssertions.update("UPDATE rt.sessions SET status = 'requires_action' WHERE id = ?", waiting);
        String pendingAction = insertAction(waiting, waitingTurn, "tool_confirmation", null, null);
        String resolvedAction = insertAction(
                completed,
                completedTurn,
                "tool_confirmation",
                EngineJson.write(new ActionConclusion(
                        ConclusionOrigin.CALLER, true, null, null, RememberScope.SESSION, null, null, null)),
                "key_test_a");
        String expiredAction = insertAction(
                failed,
                failedTurn,
                "recovery_confirmation",
                EngineJson.write(ActionConclusion.declined(
                        cn.refinex.meridian.engine.spi.event.PendingAction.Type.RECOVERY_CONFIRMATION,
                        ConclusionOrigin.TIMEOUT)),
                null);
        DbAssertions.update("UPDATE rt.pending_actions SET status = 'expired' WHERE id = ?", expiredAction);
        String hiddenAction = insertAction(deleted, deletedTurn, "tool_confirmation", null, null);
        assertThat(api.deleteSession(deleted).status()).isEqualTo(202);

        // 项目级列表：新的在前，已删除会话的动作不出现，与库中的行一致
        List<String> expectedAll = DbAssertions.list("""
                SELECT a.id FROM rt.pending_actions a JOIN rt.sessions s ON s.id = a.session_id
                 WHERE a.tenant_id = ? AND a.project_id = ? AND s.deleted_at IS NULL ORDER BY a.id DESC
                """, String.class, ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A);
        assertThat(expectedAll)
                .contains(pendingAction, resolvedAction, expiredAction)
                .doesNotContain(hiddenAction);
        assertThat(allPages(api, "")).isEqualTo(expectedAll);

        List<String> expectedPending = DbAssertions.list("""
                SELECT a.id FROM rt.pending_actions a JOIN rt.sessions s ON s.id = a.session_id
                 WHERE a.tenant_id = ? AND a.project_id = ? AND s.deleted_at IS NULL AND a.status = 'pending'
                 ORDER BY a.id DESC
                """, String.class, ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A);
        assertThat(allPages(api, "status=pending")).isEqualTo(expectedPending).contains(pendingAction);
        assertThat(allPages(api, "status=expired&type=recovery_confirmation"))
                .contains(expiredAction)
                .doesNotContain(pendingAction, resolvedAction);
        assertThat(allPages(api, "session_id=" + completed)).containsExactly(resolvedAction);
        assertThat(allPages(api, "agent_id=agt_dev_assistant")).isEqualTo(expectedAll);
        assertThat(allPages(api, "agent_id=agt_other")).isEmpty();

        // 历史中的结论与处理人
        JsonNode resolved = find(api, "status=resolved&session_id=" + completed, resolvedAction);
        assertThat(resolved.get("agent_id").asString()).isEqualTo("agt_dev_assistant");
        assertThat(resolved.get("decided_by").asString()).isEqualTo("key_test_a");
        assertThat(resolved.get("conclusion").get("origin").asString()).isEqualTo("caller");
        assertThat(resolved.get("conclusion").get("approved").asBoolean()).isTrue();
        assertThat(resolved.get("conclusion").get("remember_scope").asString()).isEqualTo("session");
        JsonNode expired = find(api, "session_id=" + failed, expiredAction);
        assertThat(expired.get("conclusion").get("origin").asString()).isEqualTo("timeout");
        assertThat(expired.get("conclusion").get("decision").asString()).isEqualTo("skip");
        assertThat(expired.get("decided_by").isNull()).isTrue();
        JsonNode pending = find(api, "status=pending", pendingAction);
        assertThat(pending.get("conclusion").isNull()).isTrue();
        // 会话内的列表带同样的字段
        assertThat(api.listPendingActions(completed, "status=resolved")
                        .body()
                        .get("data")
                        .get(0)
                        .get("decided_by")
                        .asString())
                .isEqualTo("key_test_a");

        // 会话运行概况：汇总后与库中的计数一致
        assertThat(rollup.rollUpTo(now)).isPositive();
        assertThat(rollup.rollUpTo(now)).as("同一区间只累加一次").isZero();
        Instant watermark = DbAssertions.instant(
                DbAssertions.row("SELECT watermark FROM rt.stats_watermarks WHERE name = 'agent_turn_stats'")
                        .get("watermark"));
        Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
        ApiResponse stats =
                api.call("GET /v1/session-stats", HttpMethod.GET, "/v1/session-stats?window=1h", null, null);
        assertThat(stats.status()).isEqualTo(200);
        JsonNode body = stats.body();
        assertThat(Instant.parse(body.get("window_start").asString())).isEqualTo(windowStart);
        assertThat(Instant.parse(body.get("stats_updated_at").asString())).isEqualTo(watermark);
        assertThat(body.get("running_sessions").asLong())
                .isEqualTo(count("status IN ('running', 'rescheduled')"))
                .isPositive();
        assertThat(body.get("requires_action_sessions").asLong())
                .isEqualTo(count("status = 'requires_action'"))
                .isPositive();
        assertThat(body.get("pending_actions").asLong()).isEqualTo(expectedPending.size());

        JsonNode agent = agentOf(body, "agt_dev_assistant");
        assertThat(agent.get("active_sessions").asLong())
                .isEqualTo(count("deleted_at IS NULL AND status <> 'terminated' AND agent_id = 'agt_dev_assistant'"));
        Map<String, Object> turns = DbAssertions.row(
                """
                SELECT count(*) AS turns, count(*) FILTER (WHERE t.status = 'failed') AS failed
                  FROM rt.turns t JOIN rt.sessions s ON s.id = t.session_id
                 WHERE t.tenant_id = ? AND t.project_id = ? AND s.agent_id = 'agt_dev_assistant'
                   AND t.status IN ('completed', 'interrupted', 'failed')
                   AND t.finished_at >= ? AND t.finished_at <= ?
                """, ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A, Timestamp.from(windowStart), Timestamp.from(watermark));
        long expectedTurns = ((Number) turns.get("turns")).longValue();
        long expectedFailed = ((Number) turns.get("failed")).longValue();
        assertThat(expectedTurns).isGreaterThanOrEqualTo(2);
        assertThat(agent.get("turns").asLong()).isEqualTo(expectedTurns);
        assertThat(agent.get("failed_turns").asLong()).isEqualTo(expectedFailed).isPositive();
        assertThat(agent.get("failure_rate").asDouble()).isEqualTo((double) expectedFailed / expectedTurns);

        JsonNode error = null;
        for (JsonNode candidate : body.get("recent_errors")) {
            if (candidate.get("turn_id").asString().equals(failedTurn)) {
                error = candidate;
            }
        }
        assertThat(error).as("最近错误包含失败的轮次").isNotNull();
        assertThat(error.get("session_id").asString()).isEqualTo(failed);
        assertThat(error.get("code").asString()).isEqualTo("model.rate_limit_timeout");
        assertThat(error.get("message").asString()).isEqualTo("模型限流等待超时");
        assertThat(Instant.parse(error.get("occurred_at").asString())).isEqualTo(finished);

        // 按智能体过滤时只有这个智能体，数字与不过滤时相同；别的智能体为空
        JsonNode filtered = api.call(
                        "GET /v1/session-stats",
                        HttpMethod.GET,
                        "/v1/session-stats?window=1h&agent_id=agt_dev_assistant",
                        null,
                        null)
                .body();
        assertThat(filtered.get("agents")).hasSize(1);
        assertThat(filtered.get("agents").get(0)).isEqualTo(agent);
        assertThat(filtered.get("pending_actions").asLong()).isEqualTo(expectedPending.size());
        JsonNode other = api.call(
                        "GET /v1/session-stats", HttpMethod.GET, "/v1/session-stats?agent_id=agt_other", null, null)
                .body();
        assertThat(other.get("agents")).isEmpty();
        assertThat(other.get("running_sessions").asLong()).isZero();
        assertThat(other.get("window").asString()).isEqualTo("24h");
    }

    @Test
    void sessionListFiltersByAgentStatusAndCreationTime() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        Instant before = Instant.now().minusSeconds(1);
        String first = createSession("u_filter_1");
        String second = createSession("u_filter_2");
        DbAssertions.update("UPDATE rt.sessions SET status = 'requires_action' WHERE id = ?", second);

        ApiResponse byStatus = api.listSessions("status=requires_action&limit=100");
        assertThat(ids(byStatus)).contains(second).doesNotContain(first);
        for (JsonNode session : byStatus.body().get("data")) {
            assertThat(session.get("status").asString()).isEqualTo("requires_action");
        }
        ApiResponse byAgent = api.listSessions("agent_id=agt_dev_assistant&created_after=" + before + "&limit=100");
        // 同一项目里别的测试类也在建会话：只断言顺序与每一行都满足条件
        assertThat(ids(byAgent)).containsSubsequence(second, first);
        for (JsonNode session : byAgent.body().get("data")) {
            assertThat(session.get("agent_id").asString()).isEqualTo("agt_dev_assistant");
            assertThat(Instant.parse(session.get("created_at").asString())).isAfterOrEqualTo(before);
        }
        assertThat(ids(api.listSessions("agent_id=agt_other"))).isEmpty();
        assertThat(ids(api.listSessions("created_before=" + before + "&limit=100")))
                .doesNotContain(first, second);
        assertThat(ids(api.listSessions("end_user_id=u_filter_1&status=idle"))).containsExactly(first);
        assertThat(api.getSession(first).text("agent_id")).isEqualTo("agt_dev_assistant");
    }

    @Test
    void webhookDeliveriesFilterByCreationTime() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String session = createSession("u_deliveries");
        String webhook = Ids.newId(IdPrefix.WEBHOOK);
        Instant early = Instant.parse("2026-03-01T00:00:00Z");
        Instant late = Instant.parse("2026-03-02T00:00:00Z");
        String first = insertDelivery(webhook, session, early);
        String second = insertDelivery(webhook, session, late);

        String base = "/v1/webhook-deliveries?limit=10&webhook_id=" + webhook;
        assertThat(ids(api.call("GET /v1/webhook-deliveries", HttpMethod.GET, base, null, null)))
                .containsExactly(second, first);
        assertThat(ids(api.call(
                        "GET /v1/webhook-deliveries", HttpMethod.GET, base + "&created_after=" + late, null, null)))
                .containsExactly(second);
        assertThat(ids(api.call(
                        "GET /v1/webhook-deliveries", HttpMethod.GET, base + "&created_before=" + late, null, null)))
                .as("右开")
                .containsExactly(first);
    }

    @Test
    void anApproverKeyCanOnlySendResponseEvents() {
        ApiTestClient approver = api(ApiTestKeys.KEY_A_APPROVER);
        String session = createSession("u_approver");
        assertThat(api(ApiTestKeys.KEY_A).sendMessage(session, "写个文件").status()).isEqualTo(202);
        String turn = turnOf(session);
        DbAssertions.update("UPDATE rt.turns SET status = 'awaiting_action', finished_at = now() WHERE id = ?", turn);
        DbAssertions.update("UPDATE rt.sessions SET status = 'requires_action' WHERE id = ?", session);
        String action = insertAction(session, turn, "tool_confirmation", null, null);

        // 审批人不能发消息，也不能把消息混在回应里
        ApiResponse message = approver.sendMessage(session, "顺便再写一个");
        assertThat(message.status()).isEqualTo(403);
        assertThat(message.text("code")).isEqualTo("auth.insufficient_scope");
        String confirmation = """
                {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true}]}
                """.formatted(action);
        ApiResponse readOnly = api(ApiTestKeys.KEY_A_READ_ONLY).sendEvents(session, confirmation, null);
        assertThat(readOnly.status()).isEqualTo(403);
        assertThat(readOnly.text("code")).isEqualTo("auth.insufficient_scope");

        // 只有回应事件时可以发送；结论记下审批 Key
        assertThat(approver.sendEvents(session, confirmation, null).status()).isEqualTo(202);
        JsonNode resolved = find(api(ApiTestKeys.KEY_A), "status=resolved&session_id=" + session, action);
        assertThat(resolved.get("decided_by").asString()).isEqualTo("key_test_a_approver");
        assertThat(resolved.get("conclusion").get("approved").asBoolean()).isTrue();
    }

    @Test
    void aSuggestedRuleCanBeRememberedWhenApproving() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String session = createSession("u_suggest");
        assertThat(api.sendMessage(session, "看一下状态").status()).isEqualTo(202);
        String turn = turnOf(session);
        DbAssertions.update("UPDATE rt.turns SET status = 'awaiting_action', finished_at = now() WHERE id = ?", turn);
        DbAssertions.update("UPDATE rt.sessions SET status = 'requires_action' WHERE id = ?", session);
        String action = insertAction(session, turn, "tool_confirmation", null, null);
        DbAssertions.update(
                "UPDATE rt.pending_actions SET payload = payload || CAST(? AS jsonb) WHERE id = ?",
                "{\"suggested_rules\": [\"a.md\", \"docs/**\"]}",
                action);
        assertThat(texts(find(api, "status=pending&session_id=" + session, action)
                        .get("suggested_rules")))
                .containsExactly("a.md", "docs/**");

        ApiResponse invented = api.sendEvents(session, """
                {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true,
                             "remember_scope": "session", "remember_rule": "**"}]}
                """.formatted(action), null);
        assertThat(invented.status()).isEqualTo(400);
        assertThat(invented.text("code")).isEqualTo("request.validation_failed");

        assertThat(api.sendEvents(session, """
                        {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true,
                                     "remember_scope": "session", "remember_rule": "docs/**"}]}
                        """.formatted(action), null).status())
                .isEqualTo(202);
        assertThat(DbAssertions.list(
                        "SELECT rule_content FROM rt.permission_rules WHERE session_id = ? AND tool_name = 'write_file'",
                        String.class,
                        session))
                .containsExactly("docs/**");
        JsonNode resolved = find(api, "status=resolved&session_id=" + session, action);
        assertThat(resolved.get("conclusion").get("remember_rule").asString()).isEqualTo("docs/**");
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static String insertDelivery(String webhookId, String sessionId, Instant createdAt) {
        String id = Ids.newId(IdPrefix.WEBHOOK_DELIVERY);
        DbAssertions.update(
                """
                INSERT INTO rt.webhook_deliveries (id, tenant_id, project_id, webhook_id, session_id, event_id, event_type,
                                                   payload, status, attempts, deadline_at, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'session.status_idle', '{}', 'succeeded', 1, ?, ?, ?)
                """,
                id,
                ApiTestKeys.TENANT,
                ApiTestKeys.PROJECT_A,
                webhookId,
                sessionId,
                Ids.newId(IdPrefix.EVENT),
                Timestamp.from(createdAt.plusSeconds(86400)),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    private static JsonNode agentOf(JsonNode stats, String agentId) {
        for (JsonNode agent : stats.get("agents")) {
            if (agent.get("agent_id").asString().equals(agentId)) {
                return agent;
            }
        }
        throw new AssertionError("统计中没有智能体 " + agentId + "：" + stats);
    }

    private static List<String> ids(ApiResponse page) {
        List<String> ids = new ArrayList<>();
        page.body().get("data").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    /** 逐页读完项目级待处理动作，验证游标分页。 */
    private static List<String> allPages(ApiTestClient api, String query) {
        List<String> ids = new ArrayList<>();
        String after = null;
        do {
            String path = "/v1/pending-actions?limit=2" + (query.isEmpty() ? "" : "&" + query)
                    + (after == null ? "" : "&after=" + after);
            ApiResponse page = api.call("GET /v1/pending-actions", HttpMethod.GET, path, null, null);
            assertThat(page.status()).isEqualTo(200);
            ids.addAll(ids(page));
            after = page.body().get("has_more").asBoolean() ? page.text("next_cursor") : null;
        } while (after != null);
        return ids;
    }

    private static JsonNode find(ApiTestClient api, String query, String actionId) {
        ApiResponse page = api.call(
                "GET /v1/pending-actions", HttpMethod.GET, "/v1/pending-actions?limit=100&" + query, null, null);
        for (JsonNode item : page.body().get("data")) {
            if (item.get("id").asString().equals(actionId)) {
                return item;
            }
        }
        throw new AssertionError("列表中没有动作 " + actionId + "：" + page.body());
    }

    private static long count(String condition) {
        return ((Number) DbAssertions.row(
                                "SELECT count(*) AS n FROM rt.sessions WHERE tenant_id = ? AND project_id = ? AND "
                                        + condition,
                                ApiTestKeys.TENANT,
                                ApiTestKeys.PROJECT_A)
                        .get("n"))
                .longValue();
    }

    private static String turnOf(String sessionId) {
        return DbAssertions.list("SELECT id FROM rt.turns WHERE session_id = ?", String.class, sessionId)
                .getFirst();
    }

    private static void finish(String turnId, String status, Instant finishedAt) {
        DbAssertions.update(
                "UPDATE rt.turns SET status = ?, finished_at = ?, stop_reason = ? WHERE id = ?",
                status,
                Timestamp.from(finishedAt),
                status.equals("failed") ? "error" : "end_turn",
                turnId);
    }

    private static void appendError(String sessionId, String turnId, String code, String message) {
        Map<String, Object> session = DbAssertions.row(
                "SELECT last_seq, (SELECT id FROM rt.threads WHERE session_id = ? AND parent_thread_id IS NULL) AS thread"
                        + " FROM rt.sessions WHERE id = ?",
                sessionId,
                sessionId);
        long seq = ((Number) session.get("last_seq")).longValue() + 1;
        DbAssertions.update(
                """
                INSERT INTO rt.session_events (session_id, seq, id, tenant_id, project_id, thread_id, turn_id, type, data,
                                               created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'session.error', CAST(? AS jsonb), now())
                """,
                sessionId,
                seq,
                Ids.newId(IdPrefix.EVENT),
                ApiTestKeys.TENANT,
                ApiTestKeys.PROJECT_A,
                session.get("thread"),
                turnId,
                "{\"code\": \"" + code + "\", \"message\": \"" + message + "\", \"retry_status\": \"terminal\"}");
        DbAssertions.update("UPDATE rt.sessions SET last_seq = ? WHERE id = ?", seq, sessionId);
    }

    private static String insertAction(
            String sessionId, String turnId, String type, String response, String decidedBy) {
        String id = Ids.newId(IdPrefix.PENDING_ACTION);
        String thread = DbAssertions.list(
                        "SELECT id FROM rt.threads WHERE session_id = ? AND parent_thread_id IS NULL",
                        String.class,
                        sessionId)
                .getFirst();
        DbAssertions.update(
                """
                INSERT INTO rt.pending_actions (id, tenant_id, project_id, session_id, turn_id, thread_id, type, payload,
                                                status, decided_by, response, expires_at, created_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), now() + interval '30 minutes',
                        now(), CASE WHEN ? THEN now() END)
                """,
                id,
                ApiTestKeys.TENANT,
                ApiTestKeys.PROJECT_A,
                sessionId,
                turnId,
                thread,
                type,
                "{\"tool_use_id\": \"call_" + id
                        + "\", \"tool_name\": \"write_file\", \"input\": {\"path\": \"a.md\"}}",
                response == null ? "pending" : "resolved",
                decidedBy,
                response,
                response != null);
        return id;
    }
}
