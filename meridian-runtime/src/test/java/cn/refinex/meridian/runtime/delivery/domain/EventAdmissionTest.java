package cn.refinex.meridian.runtime.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link EventAdmission}：回应事件与待处理动作的对应关系，以及中断之后的投递降级。 */
class EventAdmissionTest {

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

    @Test
    void eachResponseMustMatchAPendingActionOfTheRightType() {
        Map<String, StoredPendingAction> actions = Map.of(
                "act_tool",
                action("act_tool", PendingAction.Type.TOOL_CONFIRMATION, PendingActionStatus.PENDING),
                "act_plan",
                action("act_plan", PendingAction.Type.PLAN_EXIT, PendingActionStatus.PENDING),
                "act_custom",
                action("act_custom", PendingAction.Type.CUSTOM_TOOL_RESULT, PendingActionStatus.PENDING),
                "act_recovery",
                action("act_recovery", PendingAction.Type.RECOVERY_CONFIRMATION, PendingActionStatus.PENDING),
                "act_done",
                action("act_done", PendingAction.Type.TOOL_CONFIRMATION, PendingActionStatus.RESOLVED),
                "act_late",
                action("act_late", PendingAction.Type.TOOL_CONFIRMATION, PendingActionStatus.EXPIRED));

        assertThatCode(() -> EventAdmission.checkResponses(
                        List.of(
                                confirm("act_tool"),
                                confirm("act_plan"),
                                new UserEvent.CustomToolResult("act_custom", List.of(), false),
                                new UserEvent.RecoveryConfirmation("act_recovery", UserEvent.RecoveryDecision.SKIP),
                                message(UserEvent.Delivery.QUEUE),
                                new UserEvent.Interrupt()),
                        actions))
                .doesNotThrowAnyException();
        assertConflict(List.of(confirm("act_missing")), actions, EventErrorCode.ACTION_NOT_FOUND);
        assertConflict(List.of(confirm("act_custom")), actions, EventErrorCode.ACTION_TYPE_MISMATCH);
        assertConflict(
                List.of(new UserEvent.CustomToolResult("act_tool", List.of(), true)),
                actions,
                EventErrorCode.ACTION_TYPE_MISMATCH);
        assertConflict(
                List.of(new UserEvent.RecoveryConfirmation("act_plan", UserEvent.RecoveryDecision.RETRY)),
                actions,
                EventErrorCode.ACTION_TYPE_MISMATCH);
        assertConflict(List.of(confirm("act_done")), actions, EventErrorCode.ACTION_NOT_PENDING);
        assertConflict(List.of(confirm("act_late")), actions, EventErrorCode.ACTION_NOT_PENDING);
    }

    @Test
    void respondingToTheSameActionTwiceInOneBatchIsInvalid() {
        assertThat(EventAdmission.respondedActionIds(
                        List.of(confirm("act_1"), message(UserEvent.Delivery.QUEUE), confirm("act_2"))))
                .containsExactly("act_1", "act_2");
        assertThatThrownBy(() -> EventAdmission.respondedActionIds(List.of(confirm("act_1"), confirm("act_1"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anInterruptCannotShareABatchWithResponses() {
        // 中断会把全部待处理动作视为拒绝，同批的回应随后找不到等待中的动作（曾返回 500 并回滚整批，R1 并发审查）
        assertThatThrownBy(
                        () -> EventAdmission.respondedActionIds(List.of(confirm("act_1"), new UserEvent.Interrupt())))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(
                        () -> EventAdmission.respondedActionIds(List.of(new UserEvent.Interrupt(), confirm("act_1"))))
                .isInstanceOf(ValidationException.class);
        assertThat(EventAdmission.respondedActionIds(
                        List.of(message(UserEvent.Delivery.QUEUE), new UserEvent.Interrupt())))
                .isEmpty();
    }

    @Test
    void messagesAfterAnInterruptAreDowngradedToQueue() {
        assertThat(EventAdmission.deliveries(List.of(
                        message(UserEvent.Delivery.STEER),
                        confirm("act_1"),
                        new UserEvent.Interrupt(),
                        message(UserEvent.Delivery.STEER),
                        message(UserEvent.Delivery.QUEUE))))
                .containsExactly(
                        UserEvent.Delivery.STEER, null, null, UserEvent.Delivery.QUEUE, UserEvent.Delivery.QUEUE);
    }

    @Test
    void messagesReferencingPlatformFilesAreDowngradedToQueue() {
        UserEvent.Message withFile = new UserEvent.Message(
                List.of(new MessageContent.Text("看附件"), new MessageContent.FileRef("fil_1", null, null)),
                UserEvent.Delivery.STEER);
        UserEvent.Message withImageUrl = new UserEvent.Message(
                List.of(new MessageContent.Image("https://e.com/a.png", null, null)), UserEvent.Delivery.STEER);

        assertThat(EventAdmission.deliveries(List.of(withFile, withImageUrl)))
                .as("文件在轮次开始时才放进工作区（ADR-0050）；URL 图片不受影响")
                .containsExactly(UserEvent.Delivery.QUEUE, UserEvent.Delivery.STEER);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(EventErrorCode.values());
        assertThat(action("act_1", PendingAction.Type.PLAN_EXIT, PendingActionStatus.PENDING)
                        .input())
                .containsEntry("path", null);
    }

    private static void assertConflict(
            List<UserEvent> events, Map<String, StoredPendingAction> actions, EventErrorCode code) {
        assertThatThrownBy(() -> EventAdmission.checkResponses(events, actions))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static UserEvent confirm(String actionId) {
        return new UserEvent.ToolConfirmation(actionId, true, null, null, UserEvent.RememberScope.NONE);
    }

    private static UserEvent message(UserEvent.Delivery delivery) {
        return new UserEvent.Message(List.of(new MessageContent.Text("你好")), delivery);
    }

    private static StoredPendingAction action(String id, PendingAction.Type type, PendingActionStatus status) {
        Map<String, Object> input = new HashMap<>();
        input.put("path", null);
        return new StoredPendingAction(
                id, "ses_1", "thr_1", "trn_1", type, status, "toolu_1", "write_file", input, NOW, NOW, null);
    }
}
