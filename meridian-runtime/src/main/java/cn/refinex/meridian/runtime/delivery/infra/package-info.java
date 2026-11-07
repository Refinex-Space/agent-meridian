/**
 * 收件箱的租户作用域仓储（项目级）：每条 SQL 都带 {@code :tenant_id} 与 {@code :project_id}，值来自 {@code PlatformContext}。
 * 消息内容不在收件箱里，读取时关联对应的 {@code user.message} 事件。
 */
package cn.refinex.meridian.runtime.delivery.infra;
