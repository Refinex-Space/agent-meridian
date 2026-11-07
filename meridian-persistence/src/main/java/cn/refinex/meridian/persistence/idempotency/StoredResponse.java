package cn.refinex.meridian.persistence.idempotency;

/**
 * 被保存下来、在重放时原样返回的响应。
 *
 * @param status HTTP 状态码
 * @param body 响应体（通常是 JSON）；可为空，表示没有响应体
 */
public record StoredResponse(int status, String body) {}
