package cn.refinex.meridian.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.testsupport.model.ScriptedVectors;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code kb.chunks} 的三个索引存在且被检索语句使用（T18-A05）：向量（HNSW，余弦距离排序）、全文（{@code tsv} 的 GIN，
 * {@code public.chinese}）、访问标签（{@code acl_tags} 的 GIN，{@code &&}）。测试数据量小，规划器本会选择顺序扫描，所以在事务内关闭
 * 顺序扫描后看 {@code EXPLAIN} 的结果；计划打印到测试输出作为证据。
 */
class ChunkIndexesIT extends KnowledgeTestBase {

    @Test
    void vectorFullTextAndAclIndexesExistAndAreUsed() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        awaitFinished(tenant, base, upload(tenant, base, "travel-policy.docx", "dept:finance"));

        assertThat(db().queryForList(
                                "SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = 'kb' AND tablename = 'chunks'",
                                String.class))
                .anySatisfy(index -> assertThat(index)
                        .contains("chunks_embedding_hnsw_idx")
                        .contains("hnsw (embedding vector_cosine_ops)"))
                .anySatisfy(index ->
                        assertThat(index).contains("chunks_tsv_gin_idx").contains("gin (tsv)"))
                .anySatisfy(index ->
                        assertThat(index).contains("chunks_acl_tags_gin_idx").contains("gin (acl_tags)"));

        String vector = vectorLiteral(ScriptedVectors.embed("住宿标准", 1024));
        String vectorPlan =
                explain("SELECT id FROM kb.chunks ORDER BY embedding <=> '" + vector + "'::public.vector LIMIT 50");
        String textPlan = explain("SELECT id FROM kb.chunks WHERE tsv @@ plainto_tsquery('public.chinese', '住宿标准')");
        String aclPlan = explain("SELECT id FROM kb.chunks WHERE acl_tags && ARRAY['dept:finance']::text[]");
        System.out.println(
                "EXPLAIN 向量检索：\n" + vectorPlan + "\nEXPLAIN 全文检索：\n" + textPlan + "\nEXPLAIN 标签过滤：\n" + aclPlan);

        assertThat(vectorPlan).contains("Index Scan using chunks_embedding_hnsw_idx");
        assertThat(textPlan).contains("chunks_tsv_gin_idx");
        assertThat(aclPlan).contains("chunks_acl_tags_gin_idx");

        // 全文检索确实命中中文正文（zhparser 分词）
        assertThat(db().queryForObject(
                                "SELECT count(*) FROM kb.chunks c JOIN kb.documents d ON d.id = c.document_id"
                                        + " WHERE d.knowledge_base_id = ? AND c.tsv @@ plainto_tsquery('public.chinese', '住宿')",
                                Long.class,
                                base))
                .isPositive();
    }

    private static String explain(String sql) {
        SingleConnectionDataSource connection = new SingleConnectionDataSource();
        try (var source = db().getDataSource().getConnection()) {
            connection = new SingleConnectionDataSource(source, true);
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(connection));
            return tx.execute(status -> {
                jdbc.execute("SET LOCAL enable_seqscan = off");
                List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
                return String.join("\n", lines);
            });
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            connection.destroy();
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            text.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return text.append(']').toString();
    }
}
