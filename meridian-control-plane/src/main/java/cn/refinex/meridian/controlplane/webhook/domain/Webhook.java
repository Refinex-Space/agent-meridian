package cn.refinex.meridian.controlplane.webhook.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 项目的 Webhook（不含签名密钥）。
 *
 * @param id Webhook ID（{@code whk_…}）
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param url 接收方地址
 * @param events 订阅的事件类型，按 {@link #EVENT_TYPES} 的顺序、不重复
 * @param description 说明；可为空
 * @param enabled 是否启用
 * @param createdBy 创建者的主体标识
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 */
public record Webhook(
        String id,
        String tenantId,
        String projectId,
        String url,
        List<String> events,
        String description,
        boolean enabled,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 可订阅的事件类型（架构设计「Webhook」），顺序即保存与返回的顺序。 */
    public static final List<String> EVENT_TYPES = List.of(
            "session.status_idle", "session.status_requires_action", "session.error", "session.status_terminated");

    /**
     * 创建 Webhook，事件按 {@link #EVENT_TYPES} 规整。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 事件为空或含不可订阅的类型时
     */
    public Webhook {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        events = normalize(events);
    }

    /**
     * 规整订阅的事件：去重并按 {@link #EVENT_TYPES} 排序。
     *
     * @param events 事件类型
     * @return 规整后的不可变列表
     * @throws IllegalArgumentException 为空或含不可订阅的类型时
     */
    public static List<String> normalize(List<String> events) {
        Set<String> requested = Set.copyOf(Objects.requireNonNull(events, "events"));
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("至少订阅一种事件");
        }
        for (String event : requested) {
            if (!EVENT_TYPES.contains(event)) {
                throw new IllegalArgumentException("不可订阅的事件类型：" + event);
            }
        }
        return EVENT_TYPES.stream().filter(requested::contains).toList();
    }
}
