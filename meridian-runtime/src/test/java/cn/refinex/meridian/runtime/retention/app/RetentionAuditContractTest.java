package cn.refinex.meridian.runtime.retention.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.runtime.support.OutboxContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 会话清理与事件过期的审计消息与契约样本一致（T17a，ADR-0049）。 */
class RetentionAuditContractTest {

    @Test
    void sessionPurgedAuditMatchesTheSample() {
        OutboxContract.assertMatches(
                "audit",
                "session_purged",
                new SessionPurger.SessionPurgedAudit(
                        "session.purged",
                        Instant.parse("2026-11-12T08:00:00Z"),
                        "ten_sample",
                        "prj_sample",
                        AuditActor.service("runtime"),
                        "session",
                        "ses_sample",
                        "ses_sample",
                        Instant.parse("2026-11-12T07:00:00Z"),
                        2,
                        5));
    }

    @Test
    void eventsExpiredAuditMatchesTheSample() {
        OutboxContract.assertMatches(
                "audit",
                "session_events_expired",
                new EventRetentionSweeper.EventsExpiredAudit(
                        "session_events.expired",
                        Instant.parse("2026-11-12T08:00:00Z"),
                        "ten_sample",
                        null,
                        AuditActor.service("runtime"),
                        "tenant",
                        "ten_sample",
                        Instant.parse("2025-11-12T08:00:00Z"),
                        365,
                        3,
                        120,
                        1,
                        1));
    }
}
