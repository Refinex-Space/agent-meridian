package cn.refinex.meridian.knowledge.knowledgebase.infra.system;

import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.infra.KnowledgeBaseRepository;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 按 ID 查找知识库，不带租户条件（ADR-0051）。 */
@Repository
@CrossTenant(reason = "内部接口按知识库 ID 查询归属，调用方（control-plane、runtime）没有本服务的租户上下文，自己比较租户与项目")
public class KnowledgeBaseLookupRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public KnowledgeBaseLookupRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 按 ID 查找。
     *
     * @param id 知识库 ID
     * @return 知识库；不存在时为空
     */
    public Optional<KnowledgeBase> find(String id) {
        return jdbc().sql("""
                        SELECT kb.id, kb.tenant_id, kb.project_id, kb.name, kb.description, kb.embedding_model,
                               kb.created_by, kb.created_at, kb.updated_at, 0 AS document_count
                          FROM kb.knowledge_bases kb
                         WHERE kb.id = :id
                        """)
                .param("id", id)
                .query(KnowledgeBaseRepository::map)
                .optional();
    }
}
