package cn.refinex.meridian.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.persistence.PersistenceFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyIT {

    private static final String PRINCIPAL = "key_01JC8Z4V7Q9X3M2N6P0R5T8W1Y";

    private MutableClock clock;
    private Idempotency idempotency;
    private IdempotencyPurger purger;
    private PlatformContext tenantA;
    private PlatformContext tenantB;
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceFixtures.dataSource();
        clock = MutableClock.at(Instant.parse("2026-11-07T08:00:00Z"));
        idempotency = new Idempotency(new IdempotencyStore(dataSource, PersistenceFixtures.SCHEMA), clock);
        purger = new IdempotencyPurger(dataSource, PersistenceFixtures.SCHEMA);
        tenantA = PlatformContext.builder().tenantId(Ids.newId(IdPrefix.TENANT)).build();
        tenantB = PlatformContext.builder().tenantId(Ids.newId(IdPrefix.TENANT)).build();
    }

    @Test
    void sameKeyAndBodyReplaysTheFirstResult() {
        String hash = hash("{\"input\":\"你好\"}");

        IdempotentResult first = inTenant(tenantA, () -> execute("k1", hash, 201, "{\"id\":\"ses_1\"}"));
        IdempotentResult second = inTenant(tenantA, () -> execute("k1", hash, 201, "{\"id\":\"ses_2\"}"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(new StoredResponse(201, "{\"id\":\"ses_1\"}"));
        assertThat(executions).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() {
        inTenant(tenantA, () -> execute("k1", hash("a"), 201, "{}"));

        assertThatThrownBy(() -> inTenant(tenantA, () -> execute("k1", hash("b"), 201, "{}")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).errorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(executions).hasValue(1);
    }

    @Test
    void concurrentDuplicateWhileFirstIsRunningIsRejected() {
        String hash = hash("a");

        inTenant(
                tenantA,
                () -> idempotency.execute(PRINCIPAL, "k1", hash, () -> {
                    assertThatThrownBy(() -> execute("k1", hash, 201, "{}"))
                            .isInstanceOf(ConflictException.class)
                            .satisfies(e -> assertThat(((ConflictException) e).errorCode())
                                    .isEqualTo(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS));
                    return new StoredResponse(201, "{}");
                }));
    }

    @Test
    void failedFirstAttemptReleasesTheKey() {
        String hash = hash("a");

        assertThatThrownBy(() -> inTenant(
                        tenantA,
                        () -> idempotency.execute(PRINCIPAL, "k1", hash, () -> {
                            throw new IllegalStateException("下游失败");
                        })))
                .hasMessage("下游失败");
        IdempotentResult retry = inTenant(tenantA, () -> execute("k1", hash, 201, "{}"));

        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void keysExpireAfter24HoursAndStaleInProgressIsTakenOver() {
        String hash = hash("a");
        inTenant(tenantA, () -> execute("k1", hash, 201, "{\"v\":1}"));

        clock.advance(Idempotency.RETENTION);
        IdempotentResult afterExpiry = inTenant(tenantA, () -> execute("k1", hash("other"), 201, "{\"v\":2}"));
        assertThat(afterExpiry.replayed()).isFalse();

        // 模拟处理节点崩溃：键停在处理中，超时后允许新请求接手
        assertThatThrownBy(() -> inTenant(
                        tenantA,
                        () -> idempotency.execute(PRINCIPAL, "k2", hash, () -> {
                            throw new NodeCrash();
                        })))
                .isInstanceOf(NodeCrash.class);
        assertThatThrownBy(() -> inTenant(tenantA, () -> execute("k2", hash, 201, "{}")))
                .isInstanceOf(ConflictException.class);
        clock.advance(Idempotency.IN_PROGRESS_TIMEOUT);
        assertThat(inTenant(tenantA, () -> execute("k2", hash, 201, "{}")).replayed())
                .isFalse();
    }

    @Test
    void keysAreIsolatedByTenantAndPrincipal() {
        String hash = hash("a");
        inTenant(tenantA, () -> execute("k1", hash, 201, "{\"tenant\":\"A\"}"));

        IdempotentResult otherTenant = inTenant(tenantB, () -> execute("k1", hash, 201, "{\"tenant\":\"B\"}"));
        IdempotentResult otherPrincipal = inTenant(
                tenantA,
                () -> idempotency.execute("key_other", "k1", hash, () -> new StoredResponse(201, "{\"p\":2}")));

        assertThat(otherTenant.replayed()).isFalse();
        assertThat(otherTenant.response().body()).isEqualTo("{\"tenant\":\"B\"}");
        assertThat(otherPrincipal.replayed()).isFalse();
    }

    @Test
    void purgerDeletesOnlyExpiredKeys() {
        inTenant(tenantA, () -> execute("old", hash("a"), 201, "{}"));
        clock.advance(Duration.ofHours(23));
        inTenant(tenantA, () -> execute("new", hash("a"), 201, "{}"));
        clock.advance(Duration.ofHours(1));

        assertThat(purger.purgeExpired(clock.instant(), 1000)).isGreaterThanOrEqualTo(1);

        assertThat(inTenant(tenantA, () -> execute("new", hash("a"), 201, "{}")).replayed())
                .isTrue();
        assertThat(inTenant(tenantA, () -> execute("old", hash("a"), 201, "{}")).replayed())
                .isFalse();
    }

    @Test
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> inTenant(tenantA, () -> execute(" ", hash("a"), 201, "{}")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> inTenant(tenantA, () -> execute("x".repeat(256), hash("a"), 201, "{}")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void fingerprintCoversMethodPathAndBody() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(Idempotency.fingerprint("POST", "/v1/sessions", body))
                .hasSize(64)
                .isEqualTo(Idempotency.fingerprint("POST", "/v1/sessions", body))
                .isNotEqualTo(Idempotency.fingerprint("POST", "/v1/sessions/x", body))
                .isNotEqualTo(Idempotency.fingerprint("PUT", "/v1/sessions", body));
    }

    /** 模拟处理节点崩溃：不是 RuntimeException，幂等执行器不会释放键。 */
    private static final class NodeCrash extends Error {}

    private IdempotentResult execute(String key, String hash, int status, String body) {
        return idempotency.execute(PRINCIPAL, key, hash, () -> {
            executions.incrementAndGet();
            return new StoredResponse(status, body);
        });
    }

    private static <T> T inTenant(PlatformContext context, java.util.function.Supplier<T> action) {
        return PlatformContexts.call(context, action::get);
    }

    private static String hash(String body) {
        return Idempotency.fingerprint("POST", "/v1/sessions", body.getBytes(StandardCharsets.UTF_8));
    }
}
