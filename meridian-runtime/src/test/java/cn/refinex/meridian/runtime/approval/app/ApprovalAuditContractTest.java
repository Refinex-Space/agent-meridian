package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.audit.AuditActor;
import cn.refinex.meridian.runtime.support.OutboxContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 权限判定与待处理动作结论的审计消息与契约样本一致（T16，ADR-0048 第 6 条）。 */
class ApprovalAuditContractTest {

    private static final Instant AT = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void permissionAndConclusionAuditsMatchTheSamples() {
        OutboxContract.assertMatches(
                "audit",
                "permission_decided",
                new ApprovalAudit.PermissionDecided(
                        ApprovalAudit.PERMISSION_DECIDED,
                        AT,
                        "ten_sample",
                        "prj_sample",
                        ApprovalAudit.RUNTIME,
                        ApprovalAudit.TOOL_CALL,
                        "call_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "call_sample",
                        "bash",
                        "ask",
                        "engine"));
        OutboxContract.assertMatches(
                "audit",
                "pending_action_concluded",
                new ApprovalAudit.ActionConcluded(
                        ApprovalAudit.ACTION_CONCLUDED,
                        AT,
                        "ten_sample",
                        "prj_sample",
                        AuditActor.ofPrincipal("key_sample"),
                        "pending_action",
                        "act_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "act_sample",
                        "tool_confirmation",
                        "call_sample",
                        "bash",
                        "caller",
                        "approved",
                        null,
                        "key_sample"));
    }
}
