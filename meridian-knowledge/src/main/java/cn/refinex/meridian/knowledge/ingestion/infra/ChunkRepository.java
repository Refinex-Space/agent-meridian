package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.domain.ChunkDraft;
import cn.refinex.meridian.knowledge.ingestion.domain.SourceLocator;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code kb.chunks} 的项目级仓储（架构设计「写入流程」第 5 步）：一份文档的切片在一个事务中整体替换，同时写向量、{@code tsv}（
 * {@code public.chinese}）与访问标签。
 */
@Repository
public class ChunkRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ChunkRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 删除文档的全部切片。
     *
     * @param documentId 文档 ID
     * @return 删除的行数
     */
    public int deleteByDocument(String documentId) {
        return sql("""
                        DELETE FROM kb.chunks
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND document_id = :document_id
                        """).param("document_id", documentId).update();
    }

    /**
     * 插入一个切片。
     *
     * @param id 切片 ID
     * @param knowledgeBaseId 知识库 ID
     * @param documentId 文档 ID
     * @param draft 切分结果
     * @param embedding 向量
     * @param aclTags 访问标签
     * @param locator 来源定位
     * @param now 当前时间
     */
    public void insert(
            String id,
            String knowledgeBaseId,
            String documentId,
            ChunkDraft draft,
            float[] embedding,
            List<String> aclTags,
            SourceLocator locator,
            Instant now) {
        sql("""
                        INSERT INTO kb.chunks (id, tenant_id, project_id, knowledge_base_id, document_id, seq, kind,
                                               content, token_count, embedding, tsv, acl_tags, source_locator,
                                               created_at)
                        VALUES (:id, :tenant_id, :project_id, :knowledge_base, :document_id, :seq, :kind, :content,
                                :tokens, CAST(:embedding AS public.vector),
                                to_tsvector('public.chinese', :search_text), :acl_tags, :locator, :now)
                        """)
                .param("id", id)
                .param("knowledge_base", knowledgeBaseId)
                .param("document_id", documentId)
                .param("seq", draft.seq())
                .param("kind", draft.kind().value())
                .param("content", draft.content())
                .param("tokens", draft.tokens())
                .param("embedding", vectorLiteral(embedding))
                .param("search_text", draft.searchText())
                .param("acl_tags", JdbcValues.textArray(aclTags))
                .param("locator", JdbcValues.jsonbOf(locator))
                .param("now", now)
                .update();
    }

    /**
     * pgvector 的文本形式 {@code [x,y,…]}。
     *
     * @param vector 向量
     * @return 文本
     */
    static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
