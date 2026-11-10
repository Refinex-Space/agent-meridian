/**
 * 项目的跨租户仓储（架构设计「租户隔离」）：只有按项目 ID 找所属租户，供控制台用户的项目权限查询使用，不能被 {@code api} 层直接调用。
 */
package cn.refinex.meridian.controlplane.project.infra.system;
