package cn.refinex.meridian.knowledge.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 写入作业的重试计划（架构设计「写入流程」：失败按指数退避重试 3 次）：可重试的失败在还有尝试次数时，等待 {@code base × 2^(n-1)}
 * 后再试（n 是已经失败的次数）；不可重试的失败与用尽次数的失败结束作业。
 *
 * @param maxAttempts 最多尝试次数（首次加重试），至少 1
 * @param base 第一次重试前的等待，为正
 */
public record RetrySchedule(int maxAttempts, Duration base) {

    /** 等待的上限：避免配置的基数很大时溢出。 */
    static final Duration MAX_DELAY = Duration.ofHours(6);

    /**
     * 校验参数。
     *
     * @throws IllegalArgumentException 取值不合法时
     */
    public RetrySchedule {
        if (maxAttempts < 1 || base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("重试参数不合法：maxAttempts=" + maxAttempts + " base=" + base);
        }
    }

    /**
     * 一次失败之后的下一次尝试时间。
     *
     * @param failedAttempts 已经失败的尝试次数（含这一次），至少 1
     * @param retryable 失败是否可重试
     * @param now 当前时间
     * @return 下一次尝试的最早时间；不再重试时为空
     */
    public Optional<Instant> next(int failedAttempts, boolean retryable, Instant now) {
        if (!retryable || failedAttempts >= maxAttempts) {
            return Optional.empty();
        }
        int shift = Math.min(failedAttempts - 1, 20);
        Duration delay = base.multipliedBy(1L << shift);
        return Optional.of(now.plus(delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay));
    }
}
