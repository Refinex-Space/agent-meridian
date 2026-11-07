/**
 * 持久化基础设施：数据源与事务、租户作用域仓储 {@code TenantScopedRepository} 与跨租户 {@code SystemRepository}、outbox、Redis Stream、对象存储客户端、HTTP 幂等键。
 *
 * <p>边界：不包含任何业务表与业务仓储，也不包含迁移脚本：它们属于各应用自己的 schema。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-api}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.persistence;
