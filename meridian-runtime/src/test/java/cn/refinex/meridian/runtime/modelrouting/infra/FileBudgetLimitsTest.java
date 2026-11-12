package cn.refinex.meridian.runtime.modelrouting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 开发期预算限额的解析（{@code dev-config/budgets}）。 */
class FileBudgetLimitsTest {

    @TempDir
    Path directory;

    @Test
    void exactScopeWinsOverTheWildcardAndUnconfiguredLevelsAreUnlimited() throws IOException {
        Files.writeString(directory.resolve("sessions.yaml"), """
                kind: budget
                level: session
                scope_id: "*"
                hard_limit_tokens: 50000
                """);
        Files.writeString(directory.resolve("vip.yaml"), """
                kind: budget
                level: session
                scope_id: ses_vip
                hard_limit_tokens: 900000
                """);
        FileBudgetLimits limits = new FileBudgetLimits(directory);

        assertThat(limits.hardLimit(BudgetLevel.SESSION, "ses_vip")).isEqualTo(900_000);
        assertThat(limits.hardLimit(BudgetLevel.SESSION, "ses_other")).isEqualTo(50_000);
        assertThat(limits.hardLimit(BudgetLevel.PROJECT, "prj_any")).isEqualTo(BudgetScope.UNLIMITED);
        assertThat(new FileBudgetLimits(directory.resolve("absent")).hardLimit(BudgetLevel.TENANT, "ten_x"))
                .isEqualTo(BudgetScope.UNLIMITED);
    }
}
