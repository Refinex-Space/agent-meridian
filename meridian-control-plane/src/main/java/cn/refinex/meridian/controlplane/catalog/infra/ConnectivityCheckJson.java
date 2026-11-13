package cn.refinex.meridian.controlplane.catalog.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;

/**
 * {@code last_check} 列（jsonb）与 {@link ConnectivityCheck} 的互转，供应商账号与 MCP 服务的仓储共用（ADR-0053）。字段 snake_case，
 * 与管理 API 的 {@code ConnectivityCheck} 同名。
 */
public final class ConnectivityCheckJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private ConnectivityCheckJson() {}

    /**
     * 写入用的 jsonb 参数。
     *
     * @param check 结果
     * @return jsonb 参数
     */
    public static Object toJsonb(ConnectivityCheck check) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", check.status());
        json.put("http_status", check.httpStatus());
        json.put("latency_ms", check.latencyMs());
        json.put("message", check.message());
        json.put("checked_at", check.checkedAt().toString());
        return JdbcValues.jsonbOf(json);
    }

    /**
     * 读出的列。
     *
     * @param text jsonb 的文本；可为空
     * @return 结果；列为空时为空
     */
    public static ConnectivityCheck fromJson(String text) {
        if (text == null) {
            return null;
        }
        Map<String, Object> json = JsonMappers.lenient().readValue(text, MAP);
        Object httpStatus = json.get("http_status");
        return new ConnectivityCheck(
                (String) json.get("status"),
                httpStatus == null ? null : ((Number) httpStatus).intValue(),
                ((Number) json.get("latency_ms")).longValue(),
                (String) json.get("message"),
                Instant.parse((String) json.get("checked_at")));
    }
}
