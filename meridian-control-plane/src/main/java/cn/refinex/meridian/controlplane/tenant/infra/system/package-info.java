/**
 * 租户的跨租户仓储（架构设计「租户隔离」）：只有租户目录，供列出租户的用例使用，不能被 {@code api} 层直接调用。
 */
package cn.refinex.meridian.controlplane.tenant.infra.system;
