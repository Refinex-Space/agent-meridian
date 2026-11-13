package cn.refinex.meridian.knowledge.knowledgebase.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code kb.knowledge_bases} 的项目级仓储。租户与项目取自 {@code PlatformContext}。 */
@Repository
public class KnowledgeBaseRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            kb.id, kb.tenant_id, kb.project_id, kb.name, kb.description, kb.embedding_model, kb.rerank_model, kb.created_by,
            kb.created_at, kb.updated_at,
            (SELECT count(*) FROM kb.documents d WHERE d.knowledge_base_id = kb.id) AS document_count
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public KnowledgeBaseRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入知识库。
     *
     * @param base 知识库，租户与项目必须等于上下文中的
     */
    public void insert(KnowledgeBase base) {
        sql("""
                        INSERT INTO kb.knowledge_bases (id, tenant_id, project_id, name, description, embedding_model,
                                                        rerank_model, created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :description, :embedding_model,
                                :rerank_model, :created_by, :created_at, :updated_at)
                        """)
                .param("id", base.id())
                .param("name", base.name())
                .param("description", base.description())
                .param("embedding_model", base.embeddingModel())
                .param("rerank_model", base.rerankModel())
                .param("created_by", base.createdBy())
                .param("created_at", base.createdAt())
                .param("updated_at", base.updatedAt())
                .update();
    }

    /**
     * 读取知识库。
     *
     * @param id 知识库 ID
     * @return 知识库；不存在或不属于当前项目时为空
     */
    public Optional<KnowledgeBase> find(String id) {
        return sql("SELECT " + COLUMNS + """
                          FROM kb.knowledge_bases kb
                         WHERE kb.tenant_id = :tenant_id AND kb.project_id = :project_id AND kb.id = :id
                        """)
                .param("id", id)
                .query(KnowledgeBaseRepository::map)
                .optional();
    }

    /**
     * 按 ID（即创建时间）升序分页列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<KnowledgeBase> list(PageRequest page) {
        return sql("SELECT " + COLUMNS + """
                          FROM kb.knowledge_bases kb
                         WHERE kb.tenant_id = :tenant_id AND kb.project_id = :project_id
                           AND (CAST(:after AS text) IS NULL OR kb.id > :after)
                         ORDER BY kb.id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(KnowledgeBaseRepository::map)
                .list();
    }

    /**
     * 映射一行。
     *
     * @param rs 结果集，含 {@link #COLUMNS} 的列
     * @param row 行号
     * @return 知识库
     * @throws SQLException 读取失败时
     */
    public static KnowledgeBase map(ResultSet rs, int row) throws SQLException {
        return new KnowledgeBase(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("embedding_model"),
                rs.getString("rerank_model"),
                rs.getLong("document_count"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
