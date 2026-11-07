/**
 * 会话与线程的租户作用域仓储（项目级）：每条 SQL 都带 {@code :tenant_id} 与 {@code :project_id}，值来自 {@code PlatformContext}。
 */
package cn.refinex.meridian.runtime.session.infra;
