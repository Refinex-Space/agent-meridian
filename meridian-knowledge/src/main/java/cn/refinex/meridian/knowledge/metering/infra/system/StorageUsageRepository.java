package cn.refinex.meridian.knowledge.metering.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 知识库存储量的统计与上报记录（ADR-0051）。 */
@Repository
@CrossTenant(reason = "每日存储计量是遍历全部租户的后台作业（架构设计「计量与预算」）")
public class StorageUsageRepository extends SystemRepository {

    /**
     * 一个知识库的存储量。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param fileBytes 原文件字节数之和
     * @param indexBytes 切片正文与向量字节数之和
     */
    public record Usage(String knowledgeBaseId, String tenantId, String projectId, long fileBytes, long indexBytes) {}

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public StorageUsageRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 有内容、且这一天还没有上报的知识库。
     *
     * @param day 日期
     * @param limit 一次最多返回的条数
     * @return 存储量
     */
    public List<Usage> unreported(LocalDate day, int limit) {
        return jdbc().sql("""
                        SELECT kb.id, kb.tenant_id, kb.project_id,
                               sum(d.size_bytes) AS file_bytes, sum(d.index_bytes) AS index_bytes
                          FROM kb.knowledge_bases kb
                          JOIN kb.documents d ON d.knowledge_base_id = kb.id
                         WHERE NOT EXISTS (SELECT 1 FROM kb.storage_reports r
                                            WHERE r.knowledge_base_id = kb.id AND r.day = :day)
                         GROUP BY kb.id, kb.tenant_id, kb.project_id
                         ORDER BY kb.id
                         LIMIT :limit
                        """)
                .param("day", day)
                .param("limit", limit)
                .query((rs, row) -> new Usage(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getLong("file_bytes"),
                        rs.getLong("index_bytes")))
                .list();
    }

    /**
     * 记下这一天已上报；已有记录（其他实例同时上报）时不插入。
     *
     * @param usage 存储量
     * @param day 日期
     * @param now 当前时间
     * @return 是否插入了记录：只有插入的一方写用量消息
     */
    public boolean markReported(Usage usage, LocalDate day, Instant now) {
        return jdbc().sql("""
                                INSERT INTO kb.storage_reports (knowledge_base_id, day, tenant_id, project_id, file_bytes,
                                                                index_bytes, reported_at)
                                VALUES (:id, :day, :tenant, :project, :file_bytes, :index_bytes, :now)
                                ON CONFLICT (knowledge_base_id, day) DO NOTHING
                                """)
                        .param("id", usage.knowledgeBaseId())
                        .param("day", day)
                        .param("tenant", usage.tenantId())
                        .param("project", usage.projectId())
                        .param("file_bytes", usage.fileBytes())
                        .param("index_bytes", usage.indexBytes())
                        .param("now", JdbcValues.timestamptz(now))
                        .update()
                == 1;
    }
}
