package cn.refinex.meridian.common.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BudgetKeysTest {

    @Test
    void monthlyLevelsUseTheUtcMonthAndSessionsAreNotPeriodic() {
        Instant lastSecondOfNovember = Instant.parse("2026-11-30T23:59:59Z");
        assertThat(BudgetKeys.period(BudgetLevel.TENANT, lastSecondOfNovember)).isEqualTo("2026-11");
        assertThat(BudgetKeys.period(BudgetLevel.API_KEY, lastSecondOfNovember.plusSeconds(1)))
                .isEqualTo("2026-12");
        assertThat(BudgetKeys.period(BudgetLevel.SESSION, lastSecondOfNovember)).isEqualTo(BudgetKeys.TOTAL_PERIOD);
    }

    @Test
    void usedKeysCarryTheTenantHashTagAndParseBack() {
        String key = BudgetKeys.used("ten_1", BudgetLevel.API_KEY, "key_9", "2026-11");
        assertThat(key).isEqualTo("budget:{ten_1}:used:api_key:key_9:2026-11");
        assertThat(BudgetKeys.parseUsed(key))
                .contains(new BudgetKeys.UsedKey("ten_1", BudgetLevel.API_KEY, "key_9", "2026-11"));
        assertThat(BudgetKeys.parseUsed("budget:{ten_1}:limits")).isEmpty();
        assertThat(BudgetKeys.parseUsed("budget:{ten_1}:used:galaxy:x:total")).isEmpty();
    }

    @Test
    void limitFieldsDistinguishScopesFromLevelDefaults() {
        assertThat(BudgetKeys.limits("ten_1")).isEqualTo("budget:{ten_1}:limits");
        assertThat(BudgetKeys.limitField(BudgetLevel.TENANT, "ten_1")).isEqualTo("tenant");
        assertThat(BudgetKeys.limitField(BudgetLevel.PROJECT, "prj_1")).isEqualTo("project:prj_1");
        assertThat(BudgetKeys.limitField(BudgetLevel.SESSION, null)).isEqualTo("session:*");
        assertThat(BudgetKeys.limitValue(null)).isEqualTo(BudgetKeys.UNLIMITED);
        assertThat(BudgetKeys.limitValue(0L)).isEqualTo("0");
    }
}
