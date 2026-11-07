/**
 * 公开 API 处理器的公共能力：{@code Idempotency-Key} 的执行与重放、时间的表示转换。被各上下文的 {@code api} 包使用，
 * 不含业务逻辑；处理器本身在各上下文的 {@code api} 包中实现由 OpenAPI 生成的接口（{@code runtime.openapi}）。
 */
package cn.refinex.meridian.runtime.web;
