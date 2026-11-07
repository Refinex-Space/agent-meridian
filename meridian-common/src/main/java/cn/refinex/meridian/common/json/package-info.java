/**
 * 平台 API 的 JSON 序列化配置（Jackson 3）：snake_case、RFC 3339 UTC 时间，入站严格、出站宽松。
 *
 * <p>只提供配置好的 {@code JsonMapper}，不定义 DTO；DTO 由 {@code meridian-api} 从 OpenAPI 生成。
 */
package cn.refinex.meridian.common.json;
