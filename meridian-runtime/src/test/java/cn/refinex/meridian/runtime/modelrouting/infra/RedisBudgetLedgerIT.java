package cn.refinex.meridian.runtime.modelrouting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 四级预算 Lua 脚本（{@code budget_reserve.lua}、{@code budget_settle.lua}）的行为，Testcontainers 的 Redis。 */
class RedisBudgetLedgerIT {

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis = RuntimeTestRedis.template();
    private final RedisBudgetLedger ledger = new RedisBudgetLedger(redis, Clock.systemUTC());
    private String tenant;
    private String project;
    private String session;

    @BeforeEach
    void freshScopes() {
        tenant = "ten_" + Ids.newUlid();
        project = "prj_" + Ids.newUlid();
        session = "ses_" + Ids.newUlid();
    }

    @Test
    void reservationIncrementsEveryLevelAndIsRecorded() {
        String reservation = Ids.newUlid();

        assertThat(ledger.reserve(tenant, scopes(-1, 10_000, -1), reservation, 3_000, TTL, true))
                .isEmpty();

        assertThat(used(BudgetLevel.TENANT, tenant)).isEqualTo(3_000);
        assertThat(used(BudgetLevel.PROJECT, project)).isEqualTo(3_000);
        assertThat(used(BudgetLevel.SESSION, session)).isEqualTo(3_000);
        assertThat(redis.getExpire("budget:{" + tenant + "}:rsv:" + reservation))
                .isPositive();
    }

    @Test
    void anyInsufficientLevelRejectsTheWholeReservation() {
        ledger.reserve(tenant, scopes(-1, 5_000, -1), Ids.newUlid(), 4_000, TTL, true);

        assertThat(ledger.reserve(tenant, scopes(-1, 5_000, -1), Ids.newUlid(), 2_000, TTL, true))
                .get()
                .extracting(BudgetScope::level)
                .isEqualTo(BudgetLevel.PROJECT);
        assertThat(used(BudgetLevel.TENANT, tenant)).as("被拒绝时任何一级都不变").isEqualTo(4_000);
        assertThat(ledger.reserve(tenant, scopes(0, -1, -1), Ids.newUlid(), 1, TTL, true))
                .as("硬阈值 0 拒绝任何调用")
                .get()
                .extracting(BudgetScope::level)
                .isEqualTo(BudgetLevel.TENANT);
    }

    @Test
    void checkingDoesNotConsume() {
        assertThat(ledger.reserve(tenant, scopes(-1, 1_000, -1), "precheck", 800, TTL, false))
                .isEmpty();
        assertThat(used(BudgetLevel.PROJECT, project)).isZero();
        assertThat(ledger.reserve(tenant, scopes(-1, 1_000, -1), "precheck", 1_200, TTL, false))
                .isPresent();
    }

    @Test
    void settlementReturnsTheUnusedPartOnceAndReleaseReturnsEverything() {
        String settled = Ids.newUlid();
        String released = Ids.newUlid();
        ledger.reserve(tenant, scopes(-1, -1, -1), settled, 3_000, TTL, true);
        ledger.reserve(tenant, scopes(-1, -1, -1), released, 2_000, TTL, true);

        assertThat(ledger.settle(tenant, scopes(-1, -1, -1), settled, 1_200)).hasValue(-1_800);
        assertThat(ledger.settle(tenant, scopes(-1, -1, -1), settled, 1_200))
                .as("重复结算不再修正")
                .isEmpty();
        assertThat(ledger.settle(tenant, scopes(-1, -1, -1), released, 0)).hasValue(-2_000);

        assertThat(used(BudgetLevel.TENANT, tenant)).isEqualTo(1_200);
        assertThat(used(BudgetLevel.PROJECT, project)).isEqualTo(1_200);
        assertThat(used(BudgetLevel.SESSION, session)).isEqualTo(1_200);
        assertThat(redis.hasKey("budget:{" + tenant + "}:rsv:" + settled)).isFalse();
    }

    @Test
    void aReservationRecordsItsKeysAndSettlesInThePeriodItWasTakenIn() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-11-30T23:59:59Z"));
        RedisBudgetLedger monthly = new RedisBudgetLedger(redis, clock);
        String reservation = monthly.newReservationId();
        assertThat(reservation).endsWith("@2026-11");

        monthly.reserve(tenant, scopes(-1, -1, -1), reservation, 3_000, TTL, true);
        String november = BudgetKeys.used(tenant, BudgetLevel.TENANT, tenant, "2026-11");
        String sessionKey = BudgetKeys.used(tenant, BudgetLevel.SESSION, session, BudgetKeys.TOTAL_PERIOD);
        assertThat(redis.opsForHash().get(BudgetKeys.reservation(tenant, reservation), BudgetKeys.RESERVATION_KEYS))
                .asString()
                .contains(november, sessionKey);
        assertThat(redis.opsForSet().isMember(BudgetKeys.reservations(tenant), reservation))
                .isTrue();
        assertThat(redis.getExpire(november)).isPositive();

        // 跨月结算：修正的仍是 11 月的计数，12 月不受影响；会话的计数不分周期
        clock.advance(Duration.ofSeconds(2));
        assertThat(monthly.settle(tenant, scopes(-1, -1, -1), reservation, 1_000))
                .hasValue(-2_000);
        assertThat(redis.opsForValue().get(november)).isEqualTo("1000");
        assertThat(monthly.used(tenant, BudgetLevel.TENANT, tenant)).as("12 月").isZero();
        assertThat(monthly.used(tenant, BudgetLevel.SESSION, session)).isEqualTo(1_000);
        assertThat(redis.opsForSet().isMember(BudgetKeys.reservations(tenant), reservation))
                .isFalse();
    }

    private List<BudgetScope> scopes(long tenantLimit, long projectLimit, long sessionLimit) {
        return List.of(
                new BudgetScope(BudgetLevel.TENANT, tenant, tenantLimit),
                new BudgetScope(BudgetLevel.PROJECT, project, projectLimit),
                new BudgetScope(BudgetLevel.SESSION, session, sessionLimit));
    }

    private long used(BudgetLevel level, String scopeId) {
        return ledger.used(tenant, level, scopeId);
    }
}
