/**
 * 管理操作的审计写入：只写 outbox，不查询（审计查询在 T16）。被各上下文的用例在业务事务中调用。
 */
package cn.refinex.meridian.controlplane.audit.app;
