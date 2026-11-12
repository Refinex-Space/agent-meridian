package cn.refinex.meridian.security.web;

import java.util.Map;

/**
 * 一个 problem+json（RFC 9457）响应：HTTP 状态与有序的响应体字段。
 *
 * <p>响应体用有序 Map 而不是 Spring 的 {@code ProblemDetail}：两种 Web 栈的 JSON 编码配置不同，
 * Map 保证输出的字段名与顺序完全一致。
 *
 * @param status HTTP 状态码
 * @param body 响应体：{@code type}、{@code title}、{@code status}、{@code detail}、{@code instance}、{@code code}、
 *     {@code request_id}，校验失败时另有 {@code errors}，
 *     资源被引用时另有 {@code references}
 */
public record Problem(int status, Map<String, Object> body) {}
