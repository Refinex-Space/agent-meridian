package cn.refinex.meridian.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.controlplane.audit.domain.AuditMessages;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 审计消息的归一化（T16，ADR-0048 第 6 条）：标准字段成列，其余顶层字段并入 details。 */
class AuditDomainTest {

    @Test
    void runtimeAuditsKeepStandardFieldsAndFoldTheRestIntoDetails() {
        AuditEntry entry = AuditMessages.parse("msg_1", OutboxSamples.read("audit", "pending_action_concluded"));

        assertThat(entry.action()).isEqualTo("pending_action.concluded");
        assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-11-12T08:00:00Z"));
        assertThat(entry.actorType()).isEqualTo("api_key");
        assertThat(entry.actorSubject()).isEqualTo("key_sample");
        assertThat(entry.resourceType()).isEqualTo("pending_action");
        assertThat(entry.resourceId()).isEqualTo("act_sample");
        assertThat(entry.sessionId()).isEqualTo("ses_sample");
        assertThat(entry.details())
                .containsEntry("outcome", "approved")
                .containsEntry("remember_scope", null)
                .doesNotContainKeys("action", "actor", "tenant_id", "session_id");
    }

    @Test
    void explicitDetailsWinAndActorIsOptional() {
        AuditEntry entry = AuditMessages.parse("msg_2", """
                {"action":"api_key.created","occurred_at":"2026-11-12T08:00:00Z","tenant_id":"ten_1","extra":1,
                 "details":{"extra":2,"name":"k"}}""");

        assertThat(entry.actorType()).isNull();
        assertThat(entry.details()).containsEntry("extra", 2).containsEntry("name", "k");
    }

    @Test
    void malformedAuditsAreRejected() {
        assertThatThrownBy(() -> AuditMessages.parse(" ", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditMessages.parse("m", "{")).hasMessageContaining("JSON");
        assertThatThrownBy(() -> AuditMessages.parse("m", "1")).hasMessageContaining("对象");
        assertThatThrownBy(() -> AuditMessages.parse("m", "{\"occurred_at\":\"bad\"}"))
                .hasMessageContaining("occurred_at");
        assertThatThrownBy(() ->
                        AuditMessages.parse("m", "{\"occurred_at\":\"2026-11-12T08:00:00Z\",\"tenant_id\":\"t\"}"))
                .hasMessageContaining("action");
    }
}
