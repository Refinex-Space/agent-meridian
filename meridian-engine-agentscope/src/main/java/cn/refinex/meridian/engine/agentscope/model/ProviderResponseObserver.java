package cn.refinex.meridian.engine.agentscope.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 一次供应商请求的响应头观察：{@link ProviderHttpTransport} 收到响应时记录状态码与 {@code Retry-After}，
 * {@link RoutedModel} 据此决定重试前等多久。
 *
 * <p>AgentScope 2.0.4 的 {@code OpenAIClient} 把传输层错误翻译为不带响应头的 {@code OpenAIException}，拿不到
 * {@code Retry-After}，所以由平台的传输层记录、经 Reactor Context 传给本次调用的发起方（每次尝试一个实例）。
 *
 * <p>线程安全：字段为 volatile，传输层写、调用方在收到错误之后读。
 */
final class ProviderResponseObserver {

    private volatile int status;
    private volatile Duration retryAfter;

    /**
     * 记录响应。
     *
     * @param statusCode HTTP 状态码
     * @param retryAfterHeader {@code Retry-After} 的原文；可为空
     * @param clock 时钟，解析 HTTP 日期格式时使用
     */
    void record(int statusCode, String retryAfterHeader, Clock clock) {
        this.status = statusCode;
        this.retryAfter = parseRetryAfter(retryAfterHeader, clock);
    }

    /**
     * 状态码。
     *
     * @return 状态码；还没有收到响应时为 0
     */
    int status() {
        return status;
    }

    /**
     * 供应商要求的重试等待。
     *
     * @return 等待时长；没有 {@code Retry-After} 或无法解析时为空
     */
    Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** 解析 {@code Retry-After}：秒数或 HTTP 日期（RFC 9110 第 10.2.3 节）。负值与过去的日期视为 0。 */
    static Duration parseRetryAfter(String header, Clock clock) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration wait = Duration.between(clock.instant(), at);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }
}
