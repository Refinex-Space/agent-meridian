package cn.refinex.meridian.knowledge.knowledgebase.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
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
     * 锁住知识库行（修改与删除，ADR-0053）；锁到事务结束，并发的上传与写入结束事务随之串行。
     *
     * @param id 知识库 ID
     * @return 是否存在于当前项目
     */
    public boolean lock(String id) {
        return sql("""
                        SELECT id FROM kb.knowledge_bases
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                         FOR UPDATE
                        """).param("id", id).query(String.class).optional().isPresent();
    }

    /**
     * 修改名称、说明与 rerank 模型。
     *
     * @param id 知识库 ID
     * @param name 名称
     * @param description 说明；可为空
     * @param rerankModel rerank 模型；可为空
     * @param updatedAt 修改时间
     */
    public void update(String id, String name, String description, String rerankModel, Instant updatedAt) {
        sql("""
                        UPDATE kb.knowledge_bases
                           SET name = :name, description = :description, rerank_model = :rerank_model,
                               updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("rerank_model", rerankModel)
                .param("updated_at", updatedAt)
                .update();
    }

    /**
     * 删除知识库：文档、写入作业、切片与存储计量记录随外键级联删除。
     *
     * @param id 知识库 ID
     */
    public void delete(String id) {
        sql("DELETE FROM kb.knowledge_bases WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .update();
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
