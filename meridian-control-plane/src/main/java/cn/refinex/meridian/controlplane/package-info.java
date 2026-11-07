/**
 * 控制面应用（Spring MVC，schema {@code cp}）：租户、RBAC、API Key、模型目录、凭据、智能体版本、环境、预算、用量账本与审计。
 *
 * <p>边界：不执行智能体，不读写其他服务的 schema；与 runtime、knowledge 之间只走 HTTP，用量与审计经 outbox 与 Redis Stream 汇入。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-api}、{@code meridian-security}、{@code meridian-persistence}；禁止依赖另外两个应用。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.controlplane;
