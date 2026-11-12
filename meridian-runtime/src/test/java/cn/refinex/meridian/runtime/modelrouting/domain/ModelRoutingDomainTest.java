package cn.refinex.meridian.runtime.modelrouting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** 预算范围的推导与错误码。 */
class ModelRoutingDomainTest {

    @Test
    void scopesFollowTheLevelsPresentInTheContext() {
        PlatformContext full = PlatformContext.builder()
                .tenantId("ten_a")
                .projectId("prj_a")
                .apiKeyId("key_a")
                .sessionId("ses_a")
                .build();
        PlatformContext tenantOnly = PlatformContext.builder().tenantId("ten_b").build();

        assertThat(BudgetScope.of(full))
                .extracting(BudgetScope::level, BudgetScope::scopeId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BudgetLevel.TENANT, "ten_a"),
                        org.assertj.core.groups.Tuple.tuple(BudgetLevel.PROJECT, "prj_a"),
                        org.assertj.core.groups.Tuple.tuple(BudgetLevel.API_KEY, "key_a"),
                        org.assertj.core.groups.Tuple.tuple(BudgetLevel.SESSION, "ses_a"));
        assertThat(BudgetScope.of(tenantOnly)).extracting(BudgetScope::level).containsExactly(BudgetLevel.TENANT);
        assertThat(BudgetLevel.of("api_key")).isEqualTo(BudgetLevel.API_KEY);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(ModelRoutingErrorCode.values());
    }
}
