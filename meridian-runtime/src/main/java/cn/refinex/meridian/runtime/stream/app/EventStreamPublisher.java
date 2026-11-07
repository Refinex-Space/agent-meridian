package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 事件的实时扇出：已提交的事件与只在流上出现的事件 XADD 到 Redis Stream {@code ev:{session_id}}，SSE 订阅它（架构设计
 * 「一轮执行的生命周期」「SSE 协议」）。
 *
 * <p>数据库是事实来源：XADD 失败不回滚已提交的事件，只记录日志与指标 {@code meridian.event.publish.failures}（Prometheus 中为 {@code meridian_event_publish_failures_total}），
 * SSE 按 {@code seq} 从 PostgreSQL 补发与对账（T07）。所以本类的方法从不以错误结束。
 *
 * <p>条目字段（ADR-0028）：
 * <ul>
 *   <li>{@code type}：事件类型。
 *   <li>{@code seq}：会话内序号，只有持久化的事件有；只在流上的事件（{@code event_start}、{@code event_delta}）没有这个字段。
 *   <li>{@code envelope}：事件信封 JSON，字段与 API 一致（{@code id}、{@code seq}、{@code session_id}、{@code thread_id}、
 *       {@code turn_id}、{@code type}、{@code created_at}、{@code data}）；只在流上的事件 {@code id}、{@code seq} 为空。
 * </ul>
 * Stream 只用于实时扇出，按 {@code MAXLEN ~ 1000} 裁剪，最后一次写入 1 小时后过期，不活跃会话的键不会长期占用内存。
 *
 * <p>线程安全：无状态。命令在订阅线程上按顺序发出、在同一个 Redis 连接上按发出顺序执行，同一写入方的条目顺序与 {@code seq}
 * 一致；不同写入方（主线程与子线程）的条目可能交错，SSE 按 {@code seq} 去重与回补（T07）。
 */
@Component
public class EventStreamPublisher {

    /** 每个会话 Stream 保留的条目数上限（近似）：只做实时扇出，更早的事件由 SSE 从数据库补发。 */
    static final long MAX_LEN = 1000;

    /** 会话 Stream 在最后一次写入后的存活时间，与 SSE 单连接最长 60 分钟一致（架构设计「SSE 协议」）。 */
    static final Duration TTL = Duration.ofHours(1);

    /** 发布的等待上限：Redis 卡住时不拖住轮次的执行，超时按失败处理。 */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(EventStreamPublisher.class);

    private final ReactiveRedisMessaging redis;
    private final Clock clock;
    private final Counter failures;

    /**
     * 创建发布器。
     *
     * @param redis Redis 消息封装
     * @param clock 时钟，只在流上的事件以它为时间
     * @param meters 指标注册表
     */
    public EventStreamPublisher(ReactiveRedisMessaging redis, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.clock = clock;
        this.failures = Counter.builder("meridian.event.publish.failures")
                .description("事件写入数据库后 XADD 到 Redis Stream 失败的次数，由 SSE 的对账补齐")
                .register(meters);
    }

    /**
     * 会话事件 Stream 的键。
     *
     * @param sessionId 会话 ID
     * @return {@code ev:{session_id}}
     */
    public static String streamKey(String sessionId) {
        return "ev:" + sessionId;
    }

    /**
     * 按顺序发布已提交的事件。
     *
     * @param events 已写入数据库的事件，按 seq 升序
     * @return 全部发布（或失败已记录）后完成，不会以错误结束
     */
    public Mono<Void> publish(List<StoredEvent> events) {
        // 全部命令在调用线程上按 seq 顺序发出（同一连接按发出顺序执行），不在前一条命令完成的回调（Netty 事件循环）上发下一条
        return Flux.fromIterable(events)
                .flatMapSequential(
                        event -> xadd(event.sessionId(), event.type().value(), fields(event)), Integer.MAX_VALUE)
                .then();
    }

    /**
     * 发布只在流上出现的事件，不持久化。
     *
     * @param sessionId 会话 ID
     * @param threadId 事件所属线程；可为空
     * @param turnId 产生事件的轮次；可为空
     * @param event 事件，类型必须是只在流上出现的
     * @return 发布（或失败已记录）后完成，不会以错误结束
     */
    public Mono<Void> publishTransient(String sessionId, String threadId, String turnId, EngineEvent event) {
        Envelope envelope = new Envelope(
                null,
                null,
                sessionId,
                threadId,
                turnId,
                event.type().value(),
                clock.instant(),
                EngineJson.writeEvent(event));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", event.type().value());
        fields.put("envelope", JsonMappers.lenient().writeValueAsString(envelope));
        return xadd(sessionId, event.type().value(), fields);
    }

    private Mono<Void> xadd(String sessionId, String type, Map<String, String> fields) {
        return redis.publish(streamKey(sessionId), fields, MAX_LEN, TTL)
                .timeout(PUBLISH_TIMEOUT)
                .then()
                .onErrorResume(error -> {
                    failures.increment();
                    log.warn("事件 XADD 失败，由 SSE 从数据库补发 sessionId={} type={}", sessionId, type, error);
                    return Mono.empty();
                });
    }

    private static Map<String, String> fields(StoredEvent event) {
        Envelope envelope = new Envelope(
                event.id(),
                event.seq(),
                event.sessionId(),
                event.threadId(),
                event.turnId(),
                event.type().value(),
                event.createdAt(),
                event.data());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", event.type().value());
        fields.put("seq", Long.toString(event.seq()));
        fields.put("envelope", JsonMappers.lenient().writeValueAsString(envelope));
        return fields;
    }

    /**
     * 事件信封，字段与 API 的事件信封一致（架构设计「事件」）。
     *
     * @param id 事件 ID；只在流上的事件为空
     * @param seq 会话内序号；只在流上的事件为空
     * @param sessionId 会话 ID
     * @param threadId 线程 ID；可为空
     * @param turnId 轮次 ID；可为空
     * @param type 事件类型
     * @param createdAt 写入时间
     * @param data 事件内容 JSON 原文
     */
    record Envelope(
            String id,
            Long seq,
            String sessionId,
            String threadId,
            String turnId,
            String type,
            Instant createdAt,
            @JsonRawValue String data) {}
}
