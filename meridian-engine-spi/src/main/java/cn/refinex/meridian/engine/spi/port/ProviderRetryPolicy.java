package cn.refinex.meridian.engine.spi.port;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 首包之前的供应商重试策略（架构设计「模型调用」"错误处理"）：可重试的供应商错误（连接失败、5xx、429）原地重试至多
 * {@code maxRetries} 次，指数退避从 {@code initialBackoff} 起，429 遵循 {@code Retry-After}，要求等待超过 {@code maxInPlaceWait} 时不
 * 原地重试。内核的 {@code RoutedModel} 与 runtime 的 Embedding、rerank 共用这一份规则（R1 Q-M2）。
 *
 * @param maxRetries 最多重试次数，不小于 0；架构设计规定 2
 * @param initialBackoff 第一次重试前的等待，之后每次翻倍；必填
 * @param maxInPlaceWait 原地等待的上限；必填
 */
public record ProviderRetryPolicy(int maxRetries, Duration initialBackoff, Duration maxInPlaceWait) {

    /** 架构设计规定的策略：重试 2 次，首次等待 500 毫秒，原地等待最长 10 秒。 */
    public static final ProviderRetryPolicy DEFAULT =
            new ProviderRetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(10));

    /** 翻倍次数的上限：避免移位溢出（远大于任何合理的重试次数）。 */
    private static final int MAX_DOUBLINGS = 16;

    /**
     * 创建策略。
     *
     * @throws IllegalArgumentException 重试次数为负时
     * @throws NullPointerException 缺少时长时
     */
    public ProviderRetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负：" + maxRetries);
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxInPlaceWait, "maxInPlaceWait");
    }

    /**
     * 第 {@code retry} 次重试（从 0 起）之前的等待：指数退避与 {@code Retry-After} 取较大者。
     *
     * @param retry 已重试的次数
     * @param retryAfter 供应商要求的等待
     * @return 等待时长；已用尽重试次数，或超过原地等待上限时为空
     */
    public Optional<Duration> backoff(int retry, Optional<Duration> retryAfter) {
        if (retry >= maxRetries) {
            return Optional.empty();
        }
        Duration exponential = initialBackoff.multipliedBy(1L << Math.min(retry, MAX_DOUBLINGS));
        Duration wait =
                retryAfter.filter(after -> after.compareTo(exponential) > 0).orElse(exponential);
        return wait.compareTo(maxInPlaceWait) > 0 ? Optional.empty() : Optional.of(wait);
    }
}
