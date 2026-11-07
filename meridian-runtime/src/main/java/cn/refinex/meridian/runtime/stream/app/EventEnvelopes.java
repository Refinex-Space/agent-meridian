package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * 事件信封 JSON：字段与公开 API 的事件信封一致（架构设计「事件」），Redis Stream 条目的 {@code envelope}、SSE 的 {@code data:}
 * 与 HTTP 响应中的事件都由它生成（ADR-0028 第 6 条）。{@code data} 原样嵌入存储的事件内容，存储格式即 API 格式（ADR-0027）。
 *
 * <p>线程安全：无状态。
 */
public final class EventEnvelopes {

    private EventEnvelopes() {}

    /**
     * 持久化事件的信封。
     *
     * @param event 已写入数据库的事件
     * @return 信封 JSON
     */
    public static String json(StoredEvent event) {
        return JsonMappers.lenient()
                .writeValueAsString(new Envelope(
                        event.id(),
                        event.seq(),
                        event.sessionId(),
                        event.threadId(),
                        event.turnId(),
                        event.type().value(),
                        event.createdAt(),
                        event.data()));
    }

    /**
     * 只在流上出现的事件的信封，没有 {@code id} 与 {@code seq}。
     *
     * @param sessionId 会话 ID
     * @param threadId 事件所属线程；可为空
     * @param turnId 产生事件的轮次；可为空
     * @param event 事件，类型必须是只在流上出现的
     * @param createdAt 时间
     * @return 信封 JSON
     */
    public static String transientJson(
            String sessionId, String threadId, String turnId, EngineEvent event, Instant createdAt) {
        return JsonMappers.lenient()
                .writeValueAsString(new Envelope(
                        null,
                        null,
                        sessionId,
                        threadId,
                        turnId,
                        event.type().value(),
                        createdAt,
                        EngineJson.writeEvent(event)));
    }

    /**
     * 事件信封。
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
