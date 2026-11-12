/**
 * 写入作业的队列：认领（{@code SKIP LOCKED}）、续租、收回过期租约、以 {@code (作业, 尝试次数)} 为条件的进度与结束，同时推进文档状态
 * （架构设计「租户隔离」中"写入队列"一项）。
 */
package cn.refinex.meridian.knowledge.ingestion.infra.system;
