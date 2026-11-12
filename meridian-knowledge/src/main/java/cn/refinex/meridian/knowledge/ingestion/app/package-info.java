/**
 * 写入的用例：Worker 认领作业并执行流水线（解析 → 切分 → 批量 Embedding → 入库）、解析器的路由、租约的续租与收回（架构设计
 * 「写入流程」，ADR-0051）。解析器、doc-parser 与 runtime 的客户端经本包的接口使用。
 */
package cn.refinex.meridian.knowledge.ingestion.app;
