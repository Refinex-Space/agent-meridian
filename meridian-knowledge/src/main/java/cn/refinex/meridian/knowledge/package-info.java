/**
 * 知识库应用（Spring MVC，schema {@code kb}）：文档写入、切分、向量化与混合检索，完全自研。
 *
 * <p>边界：不依赖任何智能体框架，不直接调用模型供应商：Embedding 与 rerank 经 {@code meridian-model-client} 调用 runtime。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-api}、{@code meridian-security}、{@code meridian-persistence}、{@code meridian-model-client}；禁止依赖另外两个应用。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.knowledge;
