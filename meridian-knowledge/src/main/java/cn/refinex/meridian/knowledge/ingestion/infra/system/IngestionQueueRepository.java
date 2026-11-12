package cn.refinex.meridian.knowledge.ingestion.infra.system;

import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.document.infra.DocumentRepository;
import cn.refinex.meridian.knowledge.ingestion.domain.ClaimedJob;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionStage;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 写入作业的队列（架构设计「写入流程」：PostgreSQL 队列，{@code SKIP LOCKED}；ADR-0051）。
 *
 * <p>认领把作业改为 {@code running}、尝试次数加一并写租约；执行中的写入（进度、结束）都以作业 ID 与尝试次数为条件，被收回的尝试
 * 写不进去。文档的状态与作业同一事务推进：排队与等待重试为 {@code queued}，执行中为 {@code processing}，成功为 {@code ready}，最终失败为
 * {@code failed}。调用方负责事务。
 */
@Repository
@CrossTenant(reason = "写入作业由后台 Worker 跨租户认领与收回（架构设计「租户隔离」的写入队列）")
public class IngestionQueueRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public IngestionQueueRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 认领一个到期的排队作业，并把文档改为 {@code processing}。
     *
     * @param node 本节点标识
     * @param now 当前时间
     * @param leaseUntil 租约到期时间
     * @return 认领到的尝试；没有到期的作业时为空
     */
    public Optional<ClaimedJob> claim(String node, Instant now, Instant leaseUntil) {
        Optional<ClaimedJob> claimed = jdbc().sql("""
                        UPDATE kb.ingestion_jobs j
                           SET status = 'running', attempts = j.attempts + 1, lease_owner = :node,
                               lease_expires_at = :lease, started_at = :now, stage = NULL, chunks_total = NULL,
                               chunks_embedded = NULL, updated_at = :now
                         WHERE j.id = (SELECT id FROM kb.ingestion_jobs
                                        WHERE status = 'queued' AND not_before <= :now
                                        ORDER BY not_before, id
                                        LIMIT 1
                                          FOR UPDATE SKIP LOCKED)
                        RETURNING j.id, j.tenant_id, j.project_id, j.document_id, j.attempts, j.max_attempts
                        """)
                .param("node", node)
                .param("now", JdbcValues.timestamptz(now))
                .param("lease", JdbcValues.timestamptz(leaseUntil))
                .query((rs, row) -> new ClaimedJob(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("document_id"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")))
                .optional();
        claimed.ifPresent(job -> documentStatus(job.documentId(), "processing", now));
        return claimed;
    }

    /**
     * 锁住租约已过期的执行中作业（崩溃或长时间停顿的节点），供收回。
     *
     * @param now 当前时间
     * @param limit 一次最多处理的条数
     * @return 作业的当前尝试
     */
    public List<ClaimedJob> lockExpired(Instant now, int limit) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id, document_id, attempts, max_attempts
                          FROM kb.ingestion_jobs
                         WHERE status = 'running' AND lease_expires_at < :now
                         ORDER BY lease_expires_at
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("now", JdbcValues.timestamptz(now))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedJob(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("document_id"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")))
                .list();
    }

    /**
     * 续租本节点执行中的作业。
     *
     * @param node 本节点标识
     * @param jobIds 作业 ID
     * @param leaseUntil 新的到期时间
     * @param now 当前时间
     * @return 仍由本节点持有、续租成功的作业 ID
     */
    public List<String> renew(String node, Collection<String> jobIds, Instant leaseUntil, Instant now) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return jdbc().sql("""
                        UPDATE kb.ingestion_jobs
                           SET lease_expires_at = :lease, updated_at = :now
                         WHERE id = ANY(:ids) AND status = 'running' AND lease_owner = :node
                        RETURNING id
                        """)
                .param("ids", JdbcValues.textArray(jobIds))
                .param("node", node)
                .param("lease", JdbcValues.timestamptz(leaseUntil))
                .param("now", JdbcValues.timestamptz(now))
                .query(String.class)
                .list();
    }

    /**
     * 记录进度。
     *
     * @param job 本次尝试
     * @param stage 阶段
     * @param chunksTotal 切片数；可为空
     * @param chunksEmbedded 已计算向量的切片数；可为空
     * @param now 当前时间
     * @return 本次尝试是否仍然有效（没有被收回）
     */
    public boolean progress(
            ClaimedJob job, IngestionStage stage, Integer chunksTotal, Integer chunksEmbedded, Instant now) {
        return jdbc().sql("""
                                UPDATE kb.ingestion_jobs
                                   SET stage = :stage, chunks_total = :total, chunks_embedded = :embedded,
                                       updated_at = :now
                                 WHERE id = :id AND status = 'running' AND attempts = :attempt
                                """)
                        .param("id", job.jobId())
                        .param("attempt", job.attempt())
                        .param("stage", stage.value())
                        .param("total", chunksTotal)
                        .param("embedded", chunksEmbedded)
                        .param("now", JdbcValues.timestamptz(now))
                        .update()
                == 1;
    }

    /**
     * 锁住本次尝试的作业与文档，作为结束事务的第一步。
     *
     * @param job 本次尝试
     * @return 文档；作业已被收回、文档已删除时为空
     */
    public Optional<Document> lockForFinish(ClaimedJob job) {
        return jdbc().sql("SELECT " + DocumentRepository.COLUMNS + """
                          FROM kb.documents d
                          JOIN kb.ingestion_jobs j ON j.document_id = d.id
                         WHERE j.id = :id AND j.status = 'running' AND j.attempts = :attempt
                           FOR UPDATE OF d, j
                        """)
                .param("id", job.jobId())
                .param("attempt", job.attempt())
                .query(DocumentRepository::map)
                .optional();
    }

    /**
     * 本次尝试成功：作业结束，文档可检索。调用前已经 {@link #lockForFinish}。
     *
     * @param job 本次尝试
     * @param parser 解析器
     * @param chunkCount 切片数
     * @param indexBytes 切片的正文与向量字节数
     * @param now 当前时间
     */
    public void succeed(ClaimedJob job, String parser, int chunkCount, long indexBytes, Instant now) {
        jdbc().sql("""
                        UPDATE kb.ingestion_jobs
                           SET status = 'succeeded', stage = NULL, lease_owner = NULL, lease_expires_at = NULL,
                               chunks_embedded = chunks_total, error_code = NULL, error_message = NULL,
                               error_retryable = NULL, finished_at = :now, updated_at = :now
                         WHERE id = :id AND attempts = :attempt
                        """)
                .param("id", job.jobId())
                .param("attempt", job.attempt())
                .param("now", JdbcValues.timestamptz(now))
                .update();
        jdbc().sql("""
                        UPDATE kb.documents
                           SET status = 'ready', parser = :parser, chunk_count = :chunks, index_bytes = :index_bytes,
                               updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", job.documentId())
                .param("parser", parser)
                .param("chunks", chunkCount)
                .param("index_bytes", indexBytes)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 本次尝试失败、等待重试：作业回到队列，文档回到 {@code queued}。调用前已经锁住作业。
     *
     * @param job 本次尝试
     * @param notBefore 下一次尝试的最早时间
     * @param code 错误码
     * @param message 说明
     * @param now 当前时间
     */
    public void retryLater(ClaimedJob job, Instant notBefore, String code, String message, Instant now) {
        finishAttempt(job, "queued", notBefore, code, message, true, null, now);
        documentStatus(job.documentId(), "queued", now);
    }

    /**
     * 作业最终失败：不可重试的错误或重试用尽，文档为 {@code failed}（之前的切片保留）。调用前已经锁住作业。
     *
     * @param job 本次尝试
     * @param code 错误码
     * @param message 说明
     * @param retryable 错误本身是否可重试（重试用尽时为 true）
     * @param now 当前时间
     */
    public void fail(ClaimedJob job, String code, String message, boolean retryable, Instant now) {
        finishAttempt(job, "failed", null, code, message, retryable, now, now);
        documentStatus(job.documentId(), "failed", now);
    }

    private void finishAttempt(
            ClaimedJob job,
            String status,
            Instant notBefore,
            String code,
            String message,
            boolean retryable,
            Instant finishedAt,
            Instant now) {
        jdbc().sql("""
                        UPDATE kb.ingestion_jobs
                           SET status = :status, not_before = COALESCE(CAST(:not_before AS timestamptz), not_before), stage = NULL,
                               lease_owner = NULL, lease_expires_at = NULL, error_code = :code, error_message = :message,
                               error_retryable = :retryable, finished_at = :finished_at, updated_at = :now
                         WHERE id = :id AND status = 'running' AND attempts = :attempt
                        """)
                .param("id", job.jobId())
                .param("attempt", job.attempt())
                .param("status", status)
                .param("not_before", notBefore == null ? null : JdbcValues.timestamptz(notBefore))
                .param("code", code)
                .param("message", message)
                .param("retryable", retryable)
                .param("finished_at", finishedAt == null ? null : JdbcValues.timestamptz(finishedAt))
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    private void documentStatus(String documentId, String status, Instant now) {
        jdbc().sql("UPDATE kb.documents SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", documentId)
                .param("status", status)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }
}
