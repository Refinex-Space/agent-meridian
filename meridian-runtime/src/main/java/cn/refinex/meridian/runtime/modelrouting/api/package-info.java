/**
 * 模型接口的处理器（ADR-0033）：公开的 OpenAI 兼容直通接口（{@code /v1/chat/completions}、{@code /v1/embeddings}）与知识库用的
 * 内部接口（{@code /internal/v1/embeddings}、{@code /internal/v1/rerank}）。只做认证检查、请求翻译与响应映射，调用
 * {@code app} 的用例；内部接口按请求体建立 {@code PlatformContext}。
 */
package cn.refinex.meridian.runtime.modelrouting.api;
