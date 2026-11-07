package cn.refinex.meridian.runtime.stream.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SSE 事件流的配置（{@code meridian.runtime.sse}），默认值来自架构设计「SSE 协议」。
 *
 * @param reconcileInterval 用数据库的 {@code last_seq} 对账的间隔，默认 5 秒（「SSE 协议」第 5 步）
 * @param pingInterval {@code ping} 的间隔，默认 15 秒
 * @param maxConnectionDuration 单个连接的最长时长，默认 60 分钟，到期由服务端关闭，客户端带 {@code Last-Event-ID} 重连
 * @param pollInterval 读取 Redis Stream 的轮询间隔，默认 100 毫秒；决定实时事件的最大附加延迟，架构设计未规定
 * @param readBatch 每次轮询每个 Stream 最多读取的条目数，默认 500
 * @param refillBatch 从数据库补发时每次查询的事件数，默认 500
 * @param subscriberBuffer 每个连接缓冲的 Redis 条目上限，默认 10000；客户端消费过慢超过它时关闭连接，由客户端重连补发
 */
@ConfigurationProperties("meridian.runtime.sse")
public record SseProperties(
        @DefaultValue("5s") Duration reconcileInterval,
        @DefaultValue("15s") Duration pingInterval,
        @DefaultValue("60m") Duration maxConnectionDuration,
        @DefaultValue("100ms") Duration pollInterval,
        @DefaultValue("500") int readBatch,
        @DefaultValue("500") int refillBatch,
        @DefaultValue("10000") int subscriberBuffer) {}
