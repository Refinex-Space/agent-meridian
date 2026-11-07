package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.PROJECT;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.middleware.BatchCheckpointMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.middleware.RecoveryNoteMiddleware;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryLeaseRegistry;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateKey;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StoredState;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * T06 的两个中间件：{@code RecoveryNoteMiddleware} 在恢复轮第一次推理前注入恢复说明（不走调用入参），
 * {@code BatchCheckpointMiddleware} 在每批工具执行完成后、下一次推理前保存状态（P3）。
 */
class RecoveryMiddlewareScenarioTest {

    private static final PermissionPolicy OPEN = PermissionPolicy.empty();
    private static final String USER = PROJECT + ":_";
    private static final RecoveryNote NOTE = new RecoveryNote(List.of(
            new RecoveredToolCall("call_done", "echo", Outcome.SUCCEEDED, "echo:a"),
            new RecoveredToolCall("call_lost", "echo", Outcome.UNKNOWN, null)));

    @Test
    void recoveryOnlyInjectsTheNoteIntoTheFirstReasoningAndTheSavedState() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.text("第一轮完成"))
                .respond(r -> r.toolCall("call_r", "echo", Map.of("text", "b")))
                .respond(r -> r.text("恢复后完成"))
                .build();
        EngineHarness harness = EngineHarness.create(model, RecordingTool.echo("echo"));
        harness.run(harness.claim("thr_note", "trn_1", 1, OPEN), userText("开始"));

        List<?> events = harness.run(harness.claim("thr_note", "trn_2", 1, OPEN), new TurnInput.RecoveryOnly(NOTE));

        assertThat(events).last().isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        String noteText = model.request(1).lastUserText();
        assertThat(noteText)
                .startsWith("[平台恢复说明]")
                .contains("echo（call_done）：已成功执行，不要重复执行。结果摘要：echo:a")
                .contains("echo（call_lost）：状态未知");
        assertThat(noteMessages(model.request(2).messages()))
                .as("同一轮的第二次推理不再注入，上下文里只有第一次注入的那一条")
                .hasSize(1);
        assertThat(state("thr_note", harness))
                .as("恢复说明随状态保存")
                .map(StoredState::json)
                .hasValueSatisfying(json -> assertThat(json).contains("平台恢复说明"));
    }

    @Test
    void replayedInputIsFollowedByTheNote() {
        ScriptedModel model = ScriptedModel.builder().respond(r -> r.text("好")).build();
        EngineHarness harness = EngineHarness.create(model);

        harness.run(
                harness.claim("thr_replay", "trn_1", 2, OPEN),
                new TurnInput.NewMessages(userText("重放的消息").messages(), new RecoveryNote(List.of())));

        List<Msg> messages = model.request(0).messages();
        assertThat(messages.get(messages.size() - 2).getTextContent()).isEqualTo("重放的消息");
        assertThat(messages.getLast().getTextContent()).isEqualTo("[平台恢复说明] 上一次执行意外中断，平台已恢复本轮。中断前没有进行中的工具调用。请继续完成任务。");
    }

    @Test
    void normalTurnsGetNoNote() {
        ScriptedModel model = ScriptedModel.builder().respond(r -> r.text("好")).build();
        EngineHarness harness = EngineHarness.create(model);

        harness.run(harness.claim("thr_plain", "trn_1", 1, OPEN), userText("你好"));

        assertThat(noteMessages(model.request(0).messages())).isEmpty();
    }

    @Test
    void batchCheckpointSavesPairedToolUseAndResultBeforeTheNextReasoning() {
        AtomicReference<Optional<StoredState>> atSecondCall = new AtomicReference<>();
        AtomicReference<EngineHarness> harnessRef = new AtomicReference<>();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", "echo", Map.of("text", "a"))
                        .toolCall("call_b", "echo", Map.of("text", "b")))
                .respondTo((request, r) -> {
                    atSecondCall.set(state("thr_ckpt", harnessRef.get()));
                    r.text("完成");
                })
                .build();
        EngineHarness harness = EngineHarness.create(model, RecordingTool.echo("echo"));
        harnessRef.set(harness);
        TurnContext ctx = harness.claim("thr_ckpt", "trn_1", 4, OPEN);

        harness.run(ctx, userText("并行查两个"));

        assertThat(atSecondCall.get())
                .as("第二次模型调用之前，第一批工具的结果已经保存")
                .hasValueSatisfying(
                        stored -> assertThat(stored.json()).contains("并行查两个", "call_a", "call_b", "echo:a", "echo:b"));
        assertThat(harness.states.contentEpoch(USER, "thr_ckpt")).hasValue(4);
    }

    @Test
    void batchCheckpointCanBeTurnedOff() {
        InMemoryLeaseRegistry leases = new InMemoryLeaseRegistry();

        assertThat(new PlatformMiddlewareProvider(leases).middlewares(null))
                .hasAtLeastOneElementOfType(BatchCheckpointMiddleware.class)
                .hasAtLeastOneElementOfType(RecoveryNoteMiddleware.class);
        assertThat(new PlatformMiddlewareProvider(leases, false).middlewares(null))
                .noneMatch(BatchCheckpointMiddleware.class::isInstance)
                .hasAtLeastOneElementOfType(RecoveryNoteMiddleware.class);
    }

    private static Optional<StoredState> state(String threadId, EngineHarness harness) {
        return harness.states.find(new StateKey(USER, threadId, "agent_state"));
    }

    private static List<Msg> noteMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getMetadata() != null
                        && Boolean.TRUE.equals(m.getMetadata().get(RecoveryNoteMiddleware.METADATA_KEY)))
                .toList();
    }
}
