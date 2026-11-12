package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.domain.IngestionProgress;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionStage;
import cn.refinex.meridian.knowledge.ingestion.domain.JobStatus;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code kb.ingestion_jobs} 的项目级部分：上传与重新处理时入队，查看文档时读最近一次作业的进度。 */
@Repository
public class IngestionJobRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public IngestionJobRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 为文档建一个排队的作业。
     *
     * @param jobId 作业 ID
     * @param documentId 文档 ID
     * @param maxAttempts 最多尝试次数
     * @param now 当前时间，也是最早可被认领的时间
     * @return 插入的行数：文档已有未结束的作业时为 0（部分唯一索引）
     */
    public int enqueue(String jobId, String documentId, int maxAttempts, Instant now) {
        return sql("""
                        INSERT INTO kb.ingestion_jobs (id, tenant_id, project_id, document_id, status, attempts,
                                                       max_attempts, not_before, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :document_id, 'queued', 0, :max_attempts, :now, :now, :now)
                        ON CONFLICT (document_id) WHERE status IN ('queued', 'running') DO NOTHING
                        """)
                .param("id", jobId)
                .param("document_id", documentId)
                .param("max_attempts", maxAttempts)
                .param("now", now)
                .update();
    }

    /**
     * 每份文档最近一次作业的进度。
     *
     * @param documentIds 文档 ID
     * @return 文档 ID 到进度；没有作业的文档不在其中
     */
    public Map<String, IngestionProgress> latest(Collection<String> documentIds) {
        Map<String, IngestionProgress> progress = new HashMap<>();
        if (documentIds.isEmpty()) {
            return progress;
        }
        sql("""
                        SELECT DISTINCT ON (document_id) document_id, id, status, attempts, max_attempts, stage,
                               chunks_total, chunks_embedded, not_before, error_code, error_message, error_retryable,
                               started_at, finished_at
                          FROM kb.ingestion_jobs
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND document_id = ANY(:documents)
                         ORDER BY document_id, created_at DESC, id DESC
                        """)
                .param("documents", JdbcValues.textArray(documentIds))
                .query((rs, row) -> {
                    progress.put(rs.getString("document_id"), map(rs));
                    return null;
                })
                .list();
        return progress;
    }

    private static IngestionProgress map(ResultSet rs) throws SQLException {
        return new IngestionProgress(
                rs.getString("id"),
                JobStatus.of(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                IngestionStage.of(rs.getString("stage")),
                (Integer) rs.getObject("chunks_total"),
                (Integer) rs.getObject("chunks_embedded"),
                JdbcValues.instant(rs, "not_before"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                (Boolean) rs.getObject("error_retryable"),
                JdbcValues.instant(rs, "started_at"),
                JdbcValues.instant(rs, "finished_at"));
    }
}
