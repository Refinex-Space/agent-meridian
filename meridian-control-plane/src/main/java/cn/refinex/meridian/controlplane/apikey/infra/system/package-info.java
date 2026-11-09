/**
 * API Key 的跨租户仓储（架构设计「租户隔离」）：按哈希的认证查找，只供内部校验用例使用，不能被 {@code api} 层直接调用。
 */
package cn.refinex.meridian.controlplane.apikey.infra.system;
