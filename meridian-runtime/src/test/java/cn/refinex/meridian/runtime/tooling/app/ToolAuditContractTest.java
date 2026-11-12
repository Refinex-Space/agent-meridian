package cn.refinex.meridian.runtime.tooling.app;

import cn.refinex.meridian.runtime.support.OutboxContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 工具执行与沙箱命令的审计消息与契约样本一致（T16，ADR-0048 第 6 条）。 */
class ToolAuditContractTest {

    private static final Instant AT = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void toolAndSandboxAuditsMatchTheSamples() {
        OutboxContract.assertMatches(
                "audit",
                "tool_executed",
                new DatabaseToolLedger.ToolExecuted(
                        DatabaseToolLedger.TOOL_EXECUTED,
                        AT,
                        "ten_sample",
                        "prj_sample",
                        DatabaseToolLedger.RUNTIME,
                        DatabaseToolLedger.TOOL_CALL,
                        "call_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "call_sample",
                        "write_file",
                        "idempotent",
                        "succeeded",
                        "ses_sample:call_sample"));
        OutboxContract.assertMatches(
                "audit",
                "sandbox_command",
                new DatabaseToolLedger.SandboxCommandExecuted(
                        DatabaseToolLedger.SANDBOX_COMMAND,
                        AT,
                        "ten_sample",
                        "prj_sample",
                        DatabaseToolLedger.RUNTIME,
                        DatabaseToolLedger.TOOL_CALL,
                        "call_bash",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "call_bash",
                        "python3 report.py",
                        false,
                        0,
                        false,
                        1500));
    }
}
