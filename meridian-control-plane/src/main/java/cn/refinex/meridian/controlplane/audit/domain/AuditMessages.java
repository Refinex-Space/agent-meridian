package cn.refinex.meridian.controlplane.audit.domain;

import cn.refinex.meridian.common.json.JsonMappers;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * 把 outbox 主题 {@code audit} 的消息归一化为审计记录（ADR-0048 第 6 条）。标准字段是 {@code action}、{@code occurred_at}、
 * {@code tenant_id}、{@code project_id}、{@code actor}（{@code {type, subject}}）、{@code resource_type}、{@code resource_id}、
 * {@code session_id}、{@code details}；其余顶层字段（runtime 审计消息的平铺字段）并入 {@code details}，同名时以 {@code details} 中的为准。
 * 缺少 {@code action}、{@code occurred_at}、{@code tenant_id} 的消息抛 {@link IllegalArgumentException}，由消费者转入死信。
 */
public final class AuditMessages {

    /** 标准字段，不并入 {@code details}。 */
    private static final Set<String> STANDARD = Set.of(
            "action",
            "occurred_at",
            "tenant_id",
            "project_id",
            "actor",
            "resource_type",
            "resource_id",
            "session_id",
            "details");

    private AuditMessages() {}

    /**
     * 解析一条消息。
     *
     * @param messageId outbox 消息 ID
     * @param payload 载荷 JSON
     * @return 审计记录
     * @throws IllegalArgumentException 载荷不合法时
     */
    public static AuditEntry parse(String messageId, String payload) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("审计消息缺少 outbox 消息 ID");
        }
        JsonNode node;
        try {
            node = JsonMappers.lenient().readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("审计消息不是合法的 JSON：" + messageId, e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("审计消息不是 JSON 对象：" + messageId);
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(node, "occurred_at"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("审计消息的 occurred_at 不是 RFC 3339 时间：" + messageId, e);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!STANDARD.contains(field.getKey())) {
                details.put(field.getKey(), plain(field.getValue()));
            }
        }
        JsonNode explicit = node.get("details");
        if (explicit != null && explicit.isObject()) {
            for (Map.Entry<String, JsonNode> field : explicit.properties()) {
                details.put(field.getKey(), plain(field.getValue()));
            }
        }
        JsonNode actor = node.get("actor");
        boolean hasActor = actor != null && actor.isObject();
        return new AuditEntry(
                messageId,
                occurredAt,
                required(node, "tenant_id"),
                optional(node, "project_id"),
                required(node, "action"),
                hasActor ? optional(actor, "type") : null,
                hasActor ? optional(actor, "subject") : null,
                optional(node, "resource_type"),
                optional(node, "resource_id"),
                optional(node, "session_id"),
                details);
    }

    private static Object plain(JsonNode value) {
        return JsonMappers.lenient().treeToValue(value, Object.class);
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("审计消息缺少 " + field);
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
