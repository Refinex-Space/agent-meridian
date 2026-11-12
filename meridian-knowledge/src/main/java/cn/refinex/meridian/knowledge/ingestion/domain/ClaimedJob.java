package cn.refinex.meridian.knowledge.ingestion.domain;

/**
 * 本节点认领的一次尝试。之后对作业的写入都以 {@code (jobId, attempt)} 为条件：作业被其他节点收回再认领时 {@code attempts} 加一，
 * 本次尝试的写入全部落空（fencing，ADR-0051）。
 *
 * @param jobId 作业 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param documentId 文档 ID
 * @param attempt 本次是第几次尝试，从 1 开始
 * @param maxAttempts 最多尝试次数
 */
public record ClaimedJob(
        String jobId, String tenantId, String projectId, String documentId, int attempt, int maxAttempts) {}
