/**
 * 一轮执行中由端口实现或内核抛出、决定本轮如何结束的平台异常：预算不足、可重试的错误（限流等待超时、供应商不可用）、
 * 供应商拒绝、压缩失败，以及它们的错误码，见架构设计「状态机」中的停止原因映射与「一轮执行的生命周期」中"置回队列的三种情况"。
 *
 * <p>异常由 runtime 的 {@code ModelGateway} 实现与内核的 {@code RoutedModel} 抛出，由内核的事件映射翻译为
 * {@code session.error} 与终态事件（ADR-0026）。
 *
 * <p>边界：只有平台类型，不依赖 AgentScope 与 Spring；允许的依赖与本模块相同（{@code meridian-common}、reactor-core）。
 */
package cn.refinex.meridian.engine.spi.error;
