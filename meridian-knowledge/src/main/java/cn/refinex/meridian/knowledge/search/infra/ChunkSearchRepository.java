package cn.refinex.meridian.knowledge.search.infra;

import cn.refinex.meridian.knowledge.ingestion.domain.SourceLocator;
import cn.refinex.meridian.knowledge.ingestion.infra.ChunkRepository;
import cn.refinex.meridian.knowledge.search.domain.Candidate;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 两路召回（架构设计「检索流程」第 2 步）：租户与项目、知识库（{@code knowledge_base_id = ANY(...)}，即 {@code IN (...)}）、访问标签
 * 全部在 SQL 中过滤，不先取再筛
 * （先取再筛会让结果数不稳定，并让越权的切片出现在内存与日志中）。
 *
 * <p>访问标签：不限标签的切片（{@code acl_tags = '{}'}）对所有调用方可见，其余要与调用方的标签有交集（公开 API
 * {@code EndUserAttributes.acl_tags} 的定义）；两种条件都能用 {@code acl_tags} 的 GIN 索引（{@code array_ops} 支持 {@code =} 与
 * {@code &&}）。
 *
 * <p>向量召回按余弦距离排序，走 HNSW 索引；共用的索引上过滤选择性很高时，靠数据源初始化时设置的 {@code hnsw.iterative_scan}
 * （pgvector 0.8）继续扫描直到取满，见 {@code application.yml}。全文召回把查询经 {@code public.chinese} 分词后的词项以"或"连接，
 * 按 {@code ts_rank_cd} 排序：自然语言的问题里多数词不会全部出现在一个切片中，"且"连接几乎召回不到。{@code tsquery} 写成标量子查询
 * （InitPlan 的参数），{@code tsv @@} 才能作为 GIN 索引条件；写成连接条件时只能逐行过滤。
 */
@Repository
public class ChunkSearchRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            c.id, c.knowledge_base_id, c.document_id, c.kind, c.content, c.source_locator::text AS source_locator
            """;

    private static final String FILTER = """
            c.tenant_id = :tenant_id AND c.project_id = :project_id
               AND c.knowledge_base_id = ANY(CAST(:knowledge_base_ids AS text[]))
               AND (c.acl_tags = CAST('{}' AS text[]) OR c.acl_tags && CAST(:caller_tags AS text[]))
            """;

    /** 向量召回的语句；集成测试对它做 {@code EXPLAIN}（T19-A01）。 */
    public static final String NEAREST_SQL = "SELECT " + COLUMNS + " FROM kb.chunks c WHERE " + FILTER + """
             ORDER BY c.embedding <=> CAST(:embedding AS public.vector)
             LIMIT :limit
            """;

    /** 全文召回的语句；集成测试对它做 {@code EXPLAIN}（T19-A01）。 */
    public static final String MATCHING_SQL = """
            WITH q AS (
                SELECT CAST(string_agg(
                           '''' || replace(replace(lexeme, '\\', '\\\\'), '''', '\\''') || '''', ' | ')
                           AS tsquery) AS terms
                  FROM unnest(tsvector_to_array(to_tsvector('public.chinese', :query))) AS lexeme
            )
            """ + "SELECT " + COLUMNS + " FROM kb.chunks c WHERE " + FILTER + """
               AND c.tsv @@ (SELECT terms FROM q)
             ORDER BY ts_rank_cd(c.tsv, (SELECT terms FROM q)) DESC, c.id
             LIMIT :limit
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ChunkSearchRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 向量召回：与查询向量余弦距离最近的切片。
     *
     * @param knowledgeBaseIds 知识库 ID，属于当前项目、使用同一个 Embedding 模型
     * @param embedding 查询向量
     * @param callerTags 调用方的访问标签；可为空列表
     * @param limit 至多返回的条数
     * @return 按距离升序的候选
     */
    public List<Candidate> nearest(
            List<String> knowledgeBaseIds, float[] embedding, List<String> callerTags, int limit) {
        return sql(NEAREST_SQL)
                .param("knowledge_base_ids", JdbcValues.textArray(knowledgeBaseIds))
                .param("caller_tags", JdbcValues.textArray(callerTags))
                .param("embedding", ChunkRepository.vectorLiteral(embedding))
                .param("limit", limit)
                .query(ChunkSearchRepository::map)
                .list();
    }

    /**
     * 全文召回：查询分词后的任一词项命中的切片，按覆盖密度排序。
     *
     * <p>词项来自 {@code to_tsvector('public.chinese', 查询)}（只保留名词、动词、形容词、成语、叹词、习用语，见 PostgreSQL 初始化
     * 脚本），逐个加引号、转义反斜杠与单引号后以 {@code |} 连接，直接转成 {@code tsquery}，不再经分词。查询没有任何词项时没有结果。
     *
     * @param knowledgeBaseIds 知识库 ID，属于当前项目
     * @param query 查询文本
     * @param callerTags 调用方的访问标签；可为空列表
     * @param limit 至多返回的条数
     * @return 按相关度降序的候选
     */
    public List<Candidate> matching(List<String> knowledgeBaseIds, String query, List<String> callerTags, int limit) {
        return sql(MATCHING_SQL)
                .param("knowledge_base_ids", JdbcValues.textArray(knowledgeBaseIds))
                .param("caller_tags", JdbcValues.textArray(callerTags))
                .param("query", query)
                .param("limit", limit)
                .query(ChunkSearchRepository::map)
                .list();
    }

    private static Candidate map(ResultSet rs, int row) throws SQLException {
        SourceLocator locator = JdbcValues.readJsonb(rs, "source_locator", SourceLocator.class);
        return new Candidate(
                rs.getString("id"),
                rs.getString("knowledge_base_id"),
                rs.getString("document_id"),
                locator.filename(),
                rs.getString("kind"),
                rs.getString("content"),
                locator.headingPath(),
                locator.pageStart(),
                locator.pageEnd());
    }
}
