/**
 * knowledge 调用 runtime 内部模型接口（Embedding、rerank、聊天）的 HTTP 客户端。
 *
 * <p>边界：不直接调用模型供应商，不做限流、预算与计量：这些由 runtime 的模型层完成。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-security}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.modelclient;
