package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.WRITE;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static cn.refinex.meridian.runtime.config.WorkerFixture.types;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T08-A04、A05、A07、A08、A09：待处理动作的完整流程（架构设计「待处理动作」）。审批用没有放行规则的写入工具触发（DEFAULT 模式
 * 默认 ask，测试工具实现了 {@code matchRule} 的内容级规则见 {@code PermissionRewriteIT}）；回应经与 API 相同的发送事件用例。
 */
class ApprovalFlowIT {

    /** 会话声明的自定义工具。 */
    private static final CustomToolDeclaration NOTIFY = new CustomToolDeclaration(
            "notify_customer",
            "通知客户",
            Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
            SideEffect.NON_IDEMPOTENT);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void askCreatesAToolConfirmationAndApprovalRunsTheTool() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .respond(r -> r.text("已写入"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));

        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).outcome())
                .isEqualTo(TurnOutcome.AWAITING_ACTION);
        StoredPendingAction action = fixture.actions(session).getFirst();
        assertThat(action.type()).isEqualTo(PendingAction.Type.TOOL_CONFIRMATION);
        assertThat(action.toolName()).isEqualTo(WRITE);
        assertThat(action.input()).containsEntry("text", "新记录");
        assertThat(fixture.writeCalls.toolUseIds()).isEmpty();

        fixture.post(node, session, new UserEvent.ToolConfirmation(action.id(), true, null, null, RememberScope.NONE));
        assertThat(fixture.actions(session).getFirst().status()).isEqualTo(PendingActionStatus.RESOLVED);
        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);

        assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_w");
        assertThat(ofType(fixture.events(node, session), EventType.AGENT_TOOL_RESULT))
                .singleElement()
                .satisfies(e -> assertThat(e.data()).contains("\"permission\": \"approved\""));
    }

    @Test
    void approvalWithAnUpdatedInputRunsTheToolWithTheNewInput() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "原内容")))
                        .respond(r -> r.text("已写入"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();
        StoredPendingAction action = fixture.actions(session).getFirst();

        fixture.post(
                node,
                session,
                new UserEvent.ToolConfirmation(action.id(), true, Map.of("text", "改过的内容"), null, RememberScope.NONE));
        node.claimAndRun();

        assertThat(ofType(fixture.events(node, session), EventType.AGENT_TOOL_RESULT))
                .singleElement()
                .satisfies(e -> assertThat(e.data()).contains("write_record:改过的内容"));
    }

    @Test
    void denialGivesTheAgentADeniedResultAndTheAgentContinues() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .respondTo((request, r) -> r.text("好的，不写了"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();
        StoredPendingAction action = fixture.actions(session).getFirst();

        fixture.post(
                node, session, new UserEvent.ToolConfirmation(action.id(), false, null, "不需要写", RememberScope.NONE));
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.writeCalls.toolUseIds()).isEmpty();
        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getState()).isEqualTo(ToolResultState.DENIED);
            assertThat(block.getOutput().toString()).contains("不需要写");
        });
        assertThat(types(fixture.events(node, session)))
                .endsWith("agent.message", "span.model_request_end", "session.status_idle");
    }

    @Test
    void anUnansweredConfirmationTimesOutAsADenial() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .respond(r -> r.text("审批超时，没有写入"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();
        String awaitingTurn = (String) WorkerFixture.latestTurn(session).get("id");

        assertThat(fixture.timeouts(node).expireDue()).as("截止时间未到").isZero();
        fixture.clock.advance(WorkerProperties.defaults().pendingActionTimeout().plusSeconds(1));
        assertThat(fixture.timeouts(node).expireDue()).isEqualTo(1);
        assertThat(fixture.timeouts(node).expireDue()).as("只处理一次").isZero();

        assertThat(fixture.actions(session).getFirst().status()).isEqualTo(PendingActionStatus.EXPIRED);
        assertThat(DbAssertions.row("SELECT status FROM rt.turns WHERE id = ?", awaitingTurn))
                .containsEntry("status", "completed");
        node.claimAndRun();
        assertThat(fixture.writeCalls.toolUseIds()).isEmpty();
        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getState()).isEqualTo(ToolResultState.DENIED);
            assertThat(block.getOutput().toString()).contains("审批超时");
        });
    }

    @Test
    void confirmationsComeBeforeCustomToolResultsAndNoMessageIsLost() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录"))
                                .toolCall("call_n", NOTIFY.name(), Map.of("text", "记录已写入")))
                        .respondTo((request, r) ->
                                r.text("写入并通知完成：" + request.toolResults().size()))
                        .respondTo((request, r) -> r.text("收到追问：" + request.lastUserText()))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.ATTENDED, List.of(NOTIFY));
        fixture.post(node, session, message("写一条记录并通知客户", Delivery.QUEUE));

        node.claimAndRun();
        List<StoredPendingAction> first = fixture.actions(session);
        assertThat(first).extracting(StoredPendingAction::type).containsExactly(PendingAction.Type.TOOL_CONFIRMATION);
        // 等待回应期间到达的消息留在收件箱
        fixture.post(node, session, message("通知发出了吗？", Delivery.QUEUE));
        fixture.post(
                node,
                session,
                new UserEvent.ToolConfirmation(first.getFirst().id(), true, null, null, RememberScope.NONE));
        assertThat(WorkerFixture.latestTurn(session)).containsEntry("kind", "confirmations");

        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).outcome())
                .isEqualTo(TurnOutcome.AWAITING_ACTION);
        assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_w");
        StoredPendingAction custom = fixture.actions(session).getLast();
        assertThat(custom.type()).isEqualTo(PendingAction.Type.CUSTOM_TOOL_RESULT);
        assertThat(custom.toolUseId()).isEqualTo("call_n");

        fixture.post(
                node,
                session,
                new UserEvent.CustomToolResult(custom.id(), List.of(new MessageContent.Text("已通知")), false));
        assertThat(WorkerFixture.latestTurn(session)).containsEntry("kind", "custom_tool_results");
        node.claimAndRun();
        assertThat(WorkerFixture.latestTurn(session)).containsEntry("kind", "messages");
        node.claimAndRun();

        assertThat(DbAssertions.rows(
                                "SELECT kind FROM rt.turns WHERE thread_id = ? ORDER BY created_at, id",
                                session.mainThreadId())
                        .stream()
                        .map(row -> row.get("kind"))
                        .toList())
                .containsExactly("messages", "confirmations", "custom_tool_results", "messages");
        assertThat(fixture.model.request(1).toolResults())
                .extracting(block -> block.getId())
                .contains("call_n");
        assertThat(fixture.model.request(2).lastUserText()).isEqualTo("通知发出了吗？");
    }

    @Test
    void customToolSuspendsTheTurnAndResumesWithTheReturnedResult() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_n", NOTIFY.name(), Map.of("text", "订单已发货")))
                .respondTo((request, r) ->
                        r.text("业务方回传：" + request.toolResults().getFirst().getOutput()))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.ATTENDED, List.of(NOTIFY));
        fixture.post(node, session, message("通知客户发货了", Delivery.QUEUE));

        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).outcome())
                .isEqualTo(TurnOutcome.AWAITING_ACTION);
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(types(events)).contains("agent.custom_tool_use", "session.status_requires_action");
        StoredPendingAction action = fixture.actions(session).getFirst();
        assertThat(action.type()).isEqualTo(PendingAction.Type.CUSTOM_TOOL_RESULT);

        fixture.post(
                node,
                session,
                new UserEvent.CustomToolResult(action.id(), List.of(new MessageContent.Text("短信已发送")), false));
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getId()).isEqualTo("call_n");
            assertThat(block.getOutput().toString()).contains("短信已发送");
        });
    }

    @Test
    void anUnansweredCustomToolTimesOutWithATimeoutError() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_n", NOTIFY.name(), Map.of("text", "订单已发货")))
                .respond(r -> r.text("通知超时"))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session(Interaction.ATTENDED, List.of(NOTIFY));
        fixture.post(node, session, message("通知客户发货了", Delivery.QUEUE));
        node.claimAndRun();

        fixture.clock.advance(WorkerProperties.defaults().pendingActionTimeout().plusSeconds(1));
        fixture.timeouts(node).expireDue();
        node.claimAndRun();

        assertThat(fixture.model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
            assertThat(block.getOutput().toString()).contains("timeout");
        });
    }

    @Test
    void rememberSessionLetsLaterTurnsOfTheSameSessionRunTheToolWithoutAsking() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w1", WRITE, Map.of("text", "第一条")))
                        .respond(r -> r.text("第一条已写入"))
                        .respond(r -> r.toolCall("call_w2", WRITE, Map.of("text", "第二条")))
                        .respond(r -> r.text("第二条已写入"))
                        .respond(r -> r.toolCall("call_w3", WRITE, Map.of("text", "另一个会话")))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写第一条", Delivery.QUEUE));
        node.claimAndRun();
        StoredPendingAction action = fixture.actions(session).getFirst();

        fixture.post(
                node, session, new UserEvent.ToolConfirmation(action.id(), true, null, null, RememberScope.SESSION));
        node.claimAndRun();
        fixture.post(node, session, message("再写第二条", Delivery.QUEUE));
        TurnResult second = node.claimAndRun();

        assertThat(second)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_w1", "call_w2");
        assertThat(fixture.actions(session)).hasSize(1);
        assertThat(DbAssertions.rows(
                        "SELECT layer, tool_name, behavior, session_id, source_action_id, created_by "
                                + "FROM rt.permission_rules WHERE project_id = ?",
                        session.projectId()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("layer", "session")
                        .containsEntry("tool_name", WRITE)
                        .containsEntry("behavior", "allow")
                        .containsEntry("session_id", session.sessionId())
                        .containsEntry("source_action_id", action.id())
                        .containsEntry("created_by", "key_test"));

        // 会话级规则只对本会话生效：同项目的另一个会话仍然需要确认
        TestSession other = fixture.anotherSession(session);
        fixture.post(node, other, message("在另一个会话里写", Delivery.QUEUE));
        assertThat(node.claimAndRun())
                .extracting(r -> ((TurnResult.Finished) r).outcome())
                .isEqualTo(TurnOutcome.AWAITING_ACTION);
    }

    @Test
    void rememberProjectLetsOtherSessionsOfTheProjectRunTheToolWithoutAsking() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w1", WRITE, Map.of("text", "第一条")))
                        .respond(r -> r.text("第一条已写入"))
                        .respond(r -> r.toolCall("call_w2", WRITE, Map.of("text", "另一个会话")))
                        .respond(r -> r.text("另一个会话已写入"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写第一条", Delivery.QUEUE));
        node.claimAndRun();
        StoredPendingAction action = fixture.actions(session).getFirst();
        fixture.post(
                node, session, new UserEvent.ToolConfirmation(action.id(), true, null, null, RememberScope.PROJECT));
        node.claimAndRun();

        TestSession other = fixture.anotherSession(session);
        fixture.post(node, other, message("在另一个会话里写", Delivery.QUEUE));
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_w1", "call_w2");
        assertThat(fixture.actions(other)).isEmpty();
        assertThat(DbAssertions.row(
                        "SELECT layer, session_id, behavior FROM rt.permission_rules WHERE project_id = ?",
                        session.projectId()))
                .containsEntry("layer", "project")
                .containsEntry("session_id", null)
                .containsEntry("behavior", "allow");
    }

    @Test
    void permissionDecisionsAndResponsesAreAudited() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_l", LOOKUP, Map.of("text", "订单 42"))
                                .toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .respond(r -> r.text("完成"))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查询并写入", Delivery.QUEUE));
        node.claimAndRun();
        StoredPendingAction action = fixture.actions(session).getFirst();
        fixture.post(
                node, session, new UserEvent.ToolConfirmation(action.id(), true, null, null, RememberScope.SESSION));
        node.claimAndRun();

        List<Map<String, Object>> audits = DbAssertions.rows(
                "SELECT payload::text AS payload FROM rt.outbox WHERE topic = 'audit' "
                        + "AND payload ->> 'session_id' = ? AND payload ->> 'action' <> 'turn.finished' ORDER BY id",
                session.sessionId());
        List<String> payloads =
                audits.stream().map(row -> (String) row.get("payload")).toList();
        assertThat(payloads)
                .anySatisfy(p -> assertThat(p)
                        .contains(
                                "\"action\": \"permission.decided\"",
                                "\"tool_use_id\": \"call_w\"",
                                "\"decision\": \"ask\""))
                .anySatisfy(p -> assertThat(p)
                        .contains(
                                "\"action\": \"pending_action.concluded\"",
                                "\"action_id\": \"" + action.id() + "\"",
                                "\"origin\": \"caller\"",
                                "\"outcome\": \"approved\"",
                                "\"remember_scope\": \"session\"",
                                "\"decided_by\": \"key_test\""))
                .anySatisfy(p -> assertThat(p).contains("\"tool_use_id\": \"call_l\"", "\"decision\": \"allowed\""))
                .anySatisfy(p -> assertThat(p).contains("\"tool_use_id\": \"call_w\"", "\"decision\": \"approved\""));
    }
}
