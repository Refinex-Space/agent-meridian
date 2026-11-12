/**
 * 凭据库（架构设计「凭据」，ADR-0045）：信封加密的凭据、按租户的数据密钥、密钥加密服务（本地主密钥与 Vault Transit）、管理接口
 * （只能写入、轮换、删除）与给 runtime 的解析接口（每次解析写审计）。明文只在解析请求期间存在于内存中。
 */
package cn.refinex.meridian.controlplane.secret;
