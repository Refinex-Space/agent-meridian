package cn.refinex.meridian.runtime.session.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.error.ErrorCodes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 会话与线程的值对象：状态取值、新建参数的校验、主线程判断。 */
class SessionDomainTest {

    @Test
    void statusValuesRoundTrip() {
        for (SessionStatus status : SessionStatus.values()) {
            assertThat(SessionStatus.of(status.value())).isEqualTo(status);
        }
        assertThat(SessionStatus.REQUIRES_ACTION.value()).isEqualTo("requires_action");
        assertThatThrownBy(() -> SessionStatus.of("paused")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newSessionValidatesRequiredFieldsAndRevision() {
        NewSession session = NewSession.of("ses_1", "agv_1", "env_1", 3, null, Interaction.UNATTENDED);

        assertThat(session.environmentRevision()).isEqualTo(3);
        assertThatThrownBy(() -> NewSession.of("ses_1", "agv_1", "env_1", 0, null, Interaction.ATTENDED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewSession.of("ses_1", null, "env_1", 1, null, Interaction.ATTENDED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void childThreadNeedsBothParentAndToolUse() {
        NewThread main = NewThread.main("thr_1", "ses_1", "assistant");
        NewThread child = new NewThread("thr_2", "ses_1", "thr_1", "toolu_1", "researcher");

        assertThat(main.parentThreadId()).isNull();
        assertThat(child.parentToolUseId()).isEqualTo("toolu_1");
        assertThatThrownBy(() -> new NewThread("thr_3", "ses_1", "thr_1", null, "researcher"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewThread("thr_3", "ses_1", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void mainThreadHasNoParent() {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");

        assertThat(new SessionThread("thr_1", "ten_1", "prj_1", "ses_1", null, null, "a", SessionStatus.IDLE, now)
                        .isMain())
                .isTrue();
        assertThat(new SessionThread(
                                "thr_2", "ten_1", "prj_1", "ses_1", "thr_1", "toolu_1", "b", SessionStatus.RUNNING, now)
                        .isMain())
                .isFalse();
    }

    @Test
    void sessionKnowsWhetherItIsTerminatedOrDeleted() {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        Session archived = session(SessionStatus.TERMINATED, now, null);
        Session deleted = session(SessionStatus.TERMINATED, now, now);
        Session idle = session(SessionStatus.IDLE, null, null);

        assertThat(archived.isTerminated()).isTrue();
        assertThat(archived.isDeleted()).isFalse();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(idle.isTerminated()).isFalse();
        assertThat(idle.endUserAttributes()).isEqualTo(EndUserAttributes.NONE);
        assertThat(idle.metadata()).isEmpty();
        assertThat(idle.customTools()).isEmpty();
    }

    @Test
    void newSessionDefaultsOptionalCollections() {
        NewSession session = new NewSession(
                "ses_1",
                "agv_1",
                "env_1",
                1,
                "u_1",
                Interaction.ATTENDED,
                new EndUserAttributes(List.of("dept:finance")),
                Map.of("ticket", "INC-1"),
                null);

        assertThat(session.endUserAttributes().aclTags()).containsExactly("dept:finance");
        assertThat(session.metadata()).containsEntry("ticket", "INC-1");
        assertThat(session.customTools()).isEmpty();
        assertThat(new EndUserAttributes(null).aclTags()).isEmpty();
        assertThat(NewSession.of("ses_1", "agv_1", "env_1", 1, null, Interaction.ATTENDED)
                        .endUserAttributes())
                .isEqualTo(EndUserAttributes.NONE);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(SessionErrorCode.values());
    }

    private static Session session(SessionStatus status, Instant terminatedAt, Instant deletedAt) {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        return new Session(
                "ses_1",
                "ten_1",
                "prj_1",
                "agv_1",
                "env_1",
                1,
                null,
                Interaction.ATTENDED,
                status,
                0,
                now,
                now,
                null,
                null,
                null,
                terminatedAt,
                deletedAt,
                null);
    }
}
