package cn.refinex.meridian.controlplane.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
import cn.refinex.meridian.controlplane.budget.domain.BudgetCharge;
import cn.refinex.meridian.controlplane.budget.domain.BudgetErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 预算的领域规则（T16，ADR-0048）：阈值校验、默认值、计费项涉及的范围与周期。 */
class BudgetDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-30T23:00:00Z");

    @Test
    void limitsAreValidatedAndReplaceable() {
        Budget budget = budget(BudgetLevel.PROJECT, null, 10L, 20L);
        assertThat(budget.isLevelDefault()).isTrue();
        Budget updated = budget.withLimits(null, null, NOW.plusSeconds(1));
        assertThat(updated.softLimitTokens()).isNull();
        assertThat(updated.updatedAt()).isEqualTo(NOW.plusSeconds(1));

        assertThatThrownBy(() -> budget(BudgetLevel.PROJECT, null, 30L, 20L)).hasMessageContaining("软阈值");
        assertThatThrownBy(() -> budget(BudgetLevel.PROJECT, null, -1L, null)).hasMessageContaining("负");
        assertThatThrownBy(() -> budget(BudgetLevel.TENANT, "ten_1", null, null))
                .hasMessageContaining("范围");
        assertThat(budget(BudgetLevel.TENANT, null, null, 0L).isLevelDefault()).isFalse();
        ErrorCodes.verify(BudgetErrorCode.values());
    }

    @Test
    void aChargeTouchesEveryLevelInItsPeriod() {
        BudgetCharge charge = new BudgetCharge("ten_1", "prj_1", "key_1", "ses_1", NOW, 100);
        assertThat(charge.scopes())
                .containsExactly(
                        new BudgetCharge.Scope(BudgetLevel.TENANT, "ten_1", "2026-11"),
                        new BudgetCharge.Scope(BudgetLevel.PROJECT, "prj_1", "2026-11"),
                        new BudgetCharge.Scope(BudgetLevel.API_KEY, "key_1", "2026-11"),
                        new BudgetCharge.Scope(BudgetLevel.SESSION, "ses_1", "total"));
        assertThat(new BudgetCharge("ten_1", null, null, null, NOW, 1).scopes()).hasSize(1);
    }

    private static Budget budget(BudgetLevel level, String scope, Long soft, Long hard) {
        return new Budget("bdg_1", "ten_1", level, scope, null, soft, hard, "owner", NOW, NOW);
    }
}
