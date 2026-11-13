package cn.refinex.meridian.runtime.modelrouting.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 结算时用量记录与 Redis 计数的先后（R2 审查 C-04）：记录对瞬时错误重试；记录最终失败时 Redis 照样结算，错误交给调用方记日志。
 */
class UsageSettlementTest {

    @Test
    void aTransientRecordFailureIsRetriedBeforeSettling() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        Mono<Void> record = Mono.fromRunnable(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DataAccessResourceFailureException("connection refused");
            }
        });

        StepVerifier.create(RoutingModelGateway.settleWithUsage(record, Mono.fromRunnable(settled::incrementAndGet)))
                .verifyComplete();

        assertThat(attempts).hasValue(3);
        assertThat(settled).hasValue(1);
    }

    @Test
    void redisIsSettledEvenWhenTheRecordCannotBeWritten() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        Mono<Void> record = Mono.fromRunnable(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("outbox 写入失败");
        });

        StepVerifier.create(RoutingModelGateway.settleWithUsage(record, Mono.fromRunnable(settled::incrementAndGet)))
                .verifyErrorMessage("outbox 写入失败");

        assertThat(attempts).as("非瞬时错误不重试").hasValue(1);
        assertThat(settled).as("计数反映已经消耗的 token").hasValue(1);
    }

    @Test
    void aSuccessfulRecordSettlesOnce() {
        AtomicInteger settled = new AtomicInteger();

        StepVerifier.create(
                        RoutingModelGateway.settleWithUsage(Mono.empty(), Mono.fromRunnable(settled::incrementAndGet)))
                .verifyComplete();

        assertThat(settled).hasValue(1);
    }
}
