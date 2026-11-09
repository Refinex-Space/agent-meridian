/**
 * API Key 上下文：生成、哈希存储、作用域、吊销与广播、内部校验接口（架构设计「认证」，ADR-0042）。分 {@code api}、{@code app}、
 * {@code domain}、{@code infra} 四层；按哈希的认证查找在 {@code infra.system}。
 */
package cn.refinex.meridian.controlplane.apikey;
