package cn.refinex.meridian.knowledge.document.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.document.domain.DocumentStatus;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code kb.documents} 的项目级仓储。租户与项目取自 {@code PlatformContext}。 */
@Repository
public class DocumentRepository extends TenantScopedRepository {

    /** 查询的列，供其他仓储复用 {@link #map}。 */
    public static final String COLUMNS = """
            d.id, d.tenant_id, d.project_id, d.knowledge_base_id, d.filename, d.media_type, d.size_bytes, d.sha256,
            d.object_key, d.acl_tags, d.status, d.parser, d.chunk_count, d.index_bytes, d.created_by, d.created_at,
            d.updated_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public DocumentRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入文档。
     *
     * @param document 文档，租户与项目必须等于上下文中的
     */
    public void insert(Document document) {
        sql("""
                        INSERT INTO kb.documents (id, tenant_id, project_id, knowledge_base_id, filename, media_type,
                                                  size_bytes, sha256, object_key, acl_tags, status, parser, chunk_count,
                                                  index_bytes, created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :knowledge_base_id, :filename, :media_type, :size_bytes,
                                :sha256, :object_key, :acl_tags, :status, :parser, :chunk_count, :index_bytes,
                                :created_by, :created_at, :updated_at)
                        """)
                .param("id", document.id())
                .param("knowledge_base_id", document.knowledgeBaseId())
                .param("filename", document.filename())
                .param("media_type", document.mediaType())
                .param("size_bytes", document.sizeBytes())
                .param("sha256", document.sha256())
                .param("object_key", document.objectKey())
                .param("acl_tags", JdbcValues.textArray(document.aclTags()))
                .param("status", document.status().value())
                .param("parser", document.parser())
                .param("chunk_count", document.chunkCount())
                .param("index_bytes", document.indexBytes())
                .param("created_by", document.createdBy())
                .param("created_at", document.createdAt())
                .param("updated_at", document.updatedAt())
                .update();
    }

    /**
     * 读取知识库中的一份文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param id 文档 ID
     * @param forUpdate 是否加行锁
     * @return 文档；不存在、不在该知识库或不属于当前项目时为空
     */
    public Optional<Document> find(String knowledgeBaseId, String id, boolean forUpdate) {
        return sql("SELECT " + COLUMNS + """
                          FROM kb.documents d
                         WHERE d.tenant_id = :tenant_id AND d.project_id = :project_id
                           AND d.knowledge_base_id = :knowledge_base AND d.id = :id
                        """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("knowledge_base", knowledgeBaseId)
                .param("id", id)
                .query(DocumentRepository::map)
                .optional();
    }

    /**
     * 按 ID 读取文档（写入流水线，不经过知识库）。
     *
     * @param id 文档 ID
     * @return 文档；不存在或不属于当前项目时为空
     */
    public Optional<Document> findById(String id) {
        return sql("SELECT " + COLUMNS + """
                          FROM kb.documents d
                         WHERE d.tenant_id = :tenant_id AND d.project_id = :project_id AND d.id = :id
                        """)
                .param("id", id)
                .query(DocumentRepository::map)
                .optional();
    }

    /**
     * 按 ID（即上传时间）升序分页列出知识库中的文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Document> list(String knowledgeBaseId, PageRequest page) {
        return sql("SELECT " + COLUMNS + """
                          FROM kb.documents d
                         WHERE d.tenant_id = :tenant_id AND d.project_id = :project_id
                           AND d.knowledge_base_id = :knowledge_base
                           AND (CAST(:after AS text) IS NULL OR d.id > :after)
                         ORDER BY d.id LIMIT :limit
                        """)
                .param("knowledge_base", knowledgeBaseId)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(DocumentRepository::map)
                .list();
    }

    /**
     * 改文档状态（重新处理入队时）。
     *
     * @param id 文档 ID
     * @param status 新状态
     * @param now 当前时间
     */
    public void updateStatus(String id, DocumentStatus status, Instant now) {
        sql("""
                        UPDATE kb.documents SET status = :status, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .param("status", status.value())
                .param("now", now)
                .update();
    }

    /**
     * 删除文档；切片与写入作业经外键级联删除。
     *
     * @param id 文档 ID
     * @return 删除的行数
     */
    public int delete(String id) {
        return sql("""
                        DELETE FROM kb.documents
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """).param("id", id).update();
    }

    /**
     * 映射一行。
     *
     * @param rs 结果集，含 {@link #COLUMNS} 的列
     * @param row 行号
     * @return 文档
     * @throws SQLException 读取失败时
     */
    public static Document map(ResultSet rs, int row) throws SQLException {
        return new Document(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("knowledge_base_id"),
                rs.getString("filename"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("object_key"),
                JdbcValues.readTextArray(rs, "acl_tags"),
                DocumentStatus.of(rs.getString("status")),
                rs.getString("parser"),
                rs.getInt("chunk_count"),
                rs.getLong("index_bytes"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
