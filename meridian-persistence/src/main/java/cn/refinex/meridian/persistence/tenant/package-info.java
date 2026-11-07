/**
 * 租户隔离的数据访问基类，按架构设计「租户隔离」分三类：
 * {@link cn.refinex.meridian.persistence.tenant.TenantScopedRepository}（项目级或租户级，强制带作用域条件）与
 * {@link cn.refinex.meridian.persistence.tenant.SystemRepository}（跨租户，必须标注
 * {@link cn.refinex.meridian.persistence.tenant.CrossTenant}）。
 *
 * <p>{@code JdbcClient} 与 {@code JdbcTemplate} 只能被这两类基类的子类依赖，由 {@code meridian-arch-tests} 检查。
 * 本包不包含任何业务仓储。
 */
package cn.refinex.meridian.persistence.tenant;
