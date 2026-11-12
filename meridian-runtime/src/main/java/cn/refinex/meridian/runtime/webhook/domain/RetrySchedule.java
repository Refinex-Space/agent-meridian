package cn.refinex.meridian.runtime.webhook.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Webhook 的重试计划（架构设计「Webhook」：失败按指数退避，24 小时内最多 8 次，ADR-0049）：第 n 次失败后等待
 * {@link #DELAYS} 的第 n 项，共 8 次尝试，总等待约 10.7 小时；过了截止时间（创建后 24 小时）不再重试。
 */
public final class RetrySchedule {

    /** 最多尝试的次数。 */
    public static final int MAX_ATTEMPTS = 8;

    /** 投递记录的截止时间：创建后 24 小时。 */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** 第 1 至 7 次失败之后的等待时间。 */
    public static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(3),
            Duration.ofHours(6));

    private RetrySchedule() {}

    /**
     * 一次失败之后的下一次尝试时间。
     *
     * @param attempts 已尝试的次数（含这次失败），至少 1
     * @param now 当前时间
     * @param deadline 截止时间
     * @return 下一次尝试的时间；次数用尽或超过截止时间时为空
     */
    public static Optional<Instant> next(int attempts, Instant now, Instant deadline) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts 至少为 1：" + attempts);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        Instant next = now.plus(DELAYS.get(attempts - 1));
        return next.isAfter(deadline) ? Optional.empty() : Optional.of(next);
    }
}
