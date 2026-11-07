/**
 * 运行时应用（Spring WebFlux，schema {@code rt}）：会话、事件与 SSE、轮次调度与租约、执行智能体、平台工具、沙箱生命周期、模型调用与计量。
 *
 * <p>边界：不管理租户、凭据与模型目录等治理数据（在 control-plane），不做知识库写入与检索（在 knowledge）；与它们之间只走 HTTP。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-api}、{@code meridian-security}、{@code meridian-persistence}、{@code meridian-engine-spi}；{@code meridian-engine-agentscope} 只能在 {@code config} 包中引用其装配入口；禁止依赖另外两个应用。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.runtime;
