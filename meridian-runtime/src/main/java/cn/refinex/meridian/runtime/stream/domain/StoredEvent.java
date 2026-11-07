package cn.refinex.meridian.runtime.stream.domain;

import cn.refinex.meridian.engine.spi.event.EventType;
import java.time.Instant;

/**
 * {@code rt.session_events} 中的一行，即对外事件信封的全部字段（架构设计「事件」）。
 *
 * @param sessionId 会话 ID
 * @param seq 会话内序号，从 1 开始且连续
 * @param id 事件 ID（{@code evt_…}）
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param threadId 事件所属线程；可为空，表示会话级事件
 * @param turnId 产生事件的轮次；可为空
 * @param type 事件类型
 * @param data 事件内容 JSON（字段 snake_case）
 * @param createdAt 写入时间
 */
public record StoredEvent(
        String sessionId,
        long seq,
        String id,
        String tenantId,
        String projectId,
        String threadId,
        String turnId,
        EventType type,
        String data,
        Instant createdAt) {}
