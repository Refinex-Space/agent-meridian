package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.runtime.support.OutboxContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 轮次结束的审计消息与契约样本一致（T16，ADR-0048 第 6 条）。 */
class TurnAuditContractTest {

    @Test
    void turnFinishedAuditMatchesTheSample() {
        OutboxContract.assertMatches(
                "audit",
                "turn_finished",
                new TurnFinisher.TurnFinishedAudit(
                        "turn.finished",
                        Instant.parse("2026-11-12T08:00:00Z"),
                        "ten_sample",
                        "prj_sample",
                        AuditActor.service("runtime"),
                        "turn",
                        "trn_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "agv_sample",
                        "idle",
                        "end_turn",
                        1,
                        "node-sample",
                        null));
    }
}
