package cn.refinex.meridian.controlplane.metering.domain;

import cn.refinex.meridian.common.json.JsonMappers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * 解析 outbox 主题 {@code usage} 的消息（ADR-0048 第 3 条）。runtime 的三种消息：模型调用（{@code ModelUsageMessage}，种类
 * {@code model_tokens}、{@code embedding_tokens}、{@code rerank_tokens}）、沙箱 vCPU 秒（{@code SandboxUsageMessage}）、联网搜索
 * （{@code SearchUsageMessage}）。格式不对的消息抛 {@link IllegalArgumentException}，由消费者转入死信。
 */
public final class UsageMessages {

    private UsageMessages() {}

    /**
     * 解析一条消息。
     *
     * @param messageId outbox 消息 ID
     * @param payload 载荷 JSON
     * @return 账本记录，智能体与成本待补
     * @throws IllegalArgumentException 载荷不是对象、缺少必填字段、种类未知或取值不合法时
     */
    public static UsageRecord parse(String messageId, String payload) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("用量消息缺少 outbox 消息 ID");
        }
        JsonNode node;
        try {
            node = JsonMappers.lenient().readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("用量消息不是合法的 JSON：" + messageId, e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("用量消息不是 JSON 对象：" + messageId);
        }
        UsageKind kind;
        try {
            kind = UsageKind.of(required(node, "kind"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的用量种类：" + node.path("kind").asString(""), e);
        }
        Instant occurredAt = instant(node, "occurred_at");
        String tenantId = required(node, "tenant_id");
        return switch (kind) {
            case MODEL_TOKENS, EMBEDDING_TOKENS, RERANK_TOKENS -> {
                long input = count(node, "input_tokens");
                long output = count(node, "output_tokens");
                yield new UsageRecord(
                        messageId,
                        occurredAt,
                        kind,
                        tenantId,
                        optional(node, "project_id"),
                        optional(node, "api_key_id"),
                        null,
                        optional(node, "agent_version_id"),
                        optional(node, "session_id"),
                        optional(node, "thread_id"),
                        optional(node, "turn_id"),
                        optional(node, "request_id"),
                        required(node, "model_id"),
                        optional(node, "provider_account_id"),
                        optional(node, "upstream_model"),
                        optional(node, "purpose"),
                        node.path("fallback").asBoolean(false),
                        optional(node, "usage_source"),
                        input,
                        output,
                        count(node, "cache_read_tokens"),
                        count(node, "cache_write_tokens"),
                        count(node, "reasoning_tokens"),
                        BigDecimal.valueOf(input + output),
                        optional(node, "price_book_id"),
                        node.hasNonNull("price_book_version")
                                ? node.get("price_book_version").asInt()
                                : null,
                        null,
                        null,
                        Map.of());
            }
            case SANDBOX_VCPU_SECONDS -> {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("sandbox_id", required(node, "sandbox_id"));
                attributes.put("size", required(node, "size"));
                attributes.put("vcpu", node.path("vcpu").asInt());
                attributes.put("period_start", instant(node, "period_start").toString());
                attributes.put("period_end", instant(node, "period_end").toString());
                yield simple(messageId, occurredAt, kind, tenantId, node, decimal(node, "vcpu_seconds"), attributes);
            }
            case SEARCH_CALLS -> {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("backend", required(node, "backend"));
                long calls = node.has("count") ? count(node, "count") : 1;
                yield simple(messageId, occurredAt, kind, tenantId, node, BigDecimal.valueOf(calls), attributes);
            }
        };
    }

    private static UsageRecord simple(
            String messageId,
            Instant occurredAt,
            UsageKind kind,
            String tenantId,
            JsonNode node,
            BigDecimal quantity,
            Map<String, Object> attributes) {
        return new UsageRecord(
                messageId,
                occurredAt,
                kind,
                tenantId,
                optional(node, "project_id"),
                optional(node, "api_key_id"),
                null,
                optional(node, "agent_version_id"),
                optional(node, "session_id"),
                optional(node, "thread_id"),
                optional(node, "turn_id"),
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                0,
                0,
                0,
                0,
                0,
                quantity,
                null,
                null,
                null,
                null,
                attributes);
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用量消息缺少 " + field);
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static long count(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("用量消息的 " + field + " 必须是非负整数：" + value);
        }
        return value.asLong();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("用量消息的 " + field + " 必须是数字");
        }
        return value.decimalValue();
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(required(node, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("用量消息的 " + field + " 不是 RFC 3339 时间", e);
        }
    }
}
