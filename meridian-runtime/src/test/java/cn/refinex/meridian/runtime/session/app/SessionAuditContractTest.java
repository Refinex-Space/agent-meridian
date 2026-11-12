package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.runtime.support.OutboxContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 删除会话的审计消息与契约样本一致（T17a，ADR-0049）。 */
class SessionAuditContractTest {

    @Test
    void sessionDeletedAuditMatchesTheSample() {
        OutboxContract.assertMatches(
                "audit",
                "session_deleted",
                new SessionService.SessionDeletedAudit(
                        "session.deleted",
                        Instant.parse("2026-11-12T08:00:00Z"),
                        "ten_sample",
                        "prj_sample",
                        AuditActor.ofPrincipal("key_sample"),
                        "session",
                        "ses_sample",
                        "ses_sample",
                        "idle"));
    }
}
