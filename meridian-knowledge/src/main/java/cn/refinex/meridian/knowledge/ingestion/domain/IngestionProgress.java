package cn.refinex.meridian.knowledge.ingestion.domain;

import java.time.Instant;

/**
 * 一份文档最近一次写入作业的进度，供查看文档时返回。
 *
 * @param jobId 作业 ID（{@code ijb_…}）
 * @param status 作业状态
 * @param attempts 已开始的尝试次数
 * @param maxAttempts 最多尝试次数
 * @param stage 正在进行的阶段；未开始或已结束时为空
 * @param chunksTotal 切片数；切分之前为空
 * @param chunksEmbedded 已计算向量的切片数；切分之前为空
 * @param notBefore 等待重试时下一次尝试的最早时间
 * @param errorCode 最近一次失败的错误码；没有失败时为空
 * @param errorMessage 最近一次失败的说明；没有失败时为空
 * @param errorRetryable 最近一次失败是否可重试；没有失败时为空
 * @param startedAt 最近一次尝试开始的时间；可为空
 * @param finishedAt 结束（成功或最终失败）的时间；未结束时为空
 */
public record IngestionProgress(
        String jobId,
        JobStatus status,
        int attempts,
        int maxAttempts,
        IngestionStage stage,
        Integer chunksTotal,
        Integer chunksEmbedded,
        Instant notBefore,
        String errorCode,
        String errorMessage,
        Boolean errorRetryable,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 是否在等待重试：排队中且之前失败过。
     *
     * @return 等待重试时为 true
     */
    public boolean waitingForRetry() {
        return status == JobStatus.QUEUED && errorCode != null;
    }
}
