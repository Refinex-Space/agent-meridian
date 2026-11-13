package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.knowledge.search.infra.ChunkSearchRepository;
import cn.refinex.meridian.knowledge.support.ApiClient;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.testsupport.model.ScriptedVectors;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 混合检索（T19）：过滤条件下推到 SQL 且走索引（A01）、公开 API 与内部接口的权限隔离（A02 的 REST 与 runtime 入口）、rerank、查询改写、
 * 检索计量。语料是检索评测的 6 份制度（{@code retrieval-eval/corpus}），向量与 rerank 由 {@code StubPlatform} 确定地给出。
 */
class KnowledgeSearchIT extends KnowledgeTestBase {

    /** 评测语料目录。 */
    static final Path EVAL_CORPUS = Path.of("src/test/resources/retrieval-eval/corpus");

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void reset() {
        PLATFORM.reranker(null);
        PLATFORM.chat(null);
    }

    @Test
    void filtersArePushedIntoSqlAndBothRecallsUseIndexes() throws SQLException {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        ingest(tenant, base, "travel-policy.md", evalCorpus("travel-policy.md"), null);
        ingest(tenant, base, "compensation.md", evalCorpus("compensation.md"), "level:confidential");

        String vector = vectorLiteral(ScriptedVectors.embed("住宿标准", 1024));
        String nearest = bind(ChunkSearchRepository.NEAREST_SQL, tenant, base)
                .replace(":embedding", "'" + vector + "'")
                .replace(":limit", "50");
        String matching = bind(ChunkSearchRepository.MATCHING_SQL, tenant, base)
                .replace(":query", "'去上海出差住宿标准是多少'")
                .replace(":limit", "50");
        String nearestPlan = explain(nearest);
        String matchingPlan = explain(matching);
        // 小数据量下规划器选 (tenant_id, knowledge_base_id) 的 btree 再排序；不许排序时只能靠 HNSW 给出距离顺序，证明同一语句
        // 能用上 HNSW（知识库大时规划器自己会选它）。tsv 的 GIN 能服务全文条件由 ChunkIndexesIT 验证（T18-A05）
        String hnswPlan = explain(nearest, "SET LOCAL enable_sort = off");
        System.out.println("EXPLAIN 向量召回：\n" + nearestPlan + "\nEXPLAIN 向量召回（enable_sort = off）：\n" + hnswPlan
                + "\nEXPLAIN 全文召回：\n" + matchingPlan);

        for (String plan : List.of(nearestPlan, matchingPlan, hnswPlan)) {
            assertThat(plan)
                    .containsPattern("(Index Scan|Bitmap Index Scan) using chunks_")
                    .doesNotContain("Seq Scan on chunks");
            // 租户、项目、知识库、访问标签四个条件都在扫描 kb.chunks 的节点上，而不是取回之后再筛
            assertThat(plan)
                    .contains("tenant_id = ")
                    .contains("project_id = ")
                    .contains("knowledge_base_id = ANY")
                    .contains("acl_tags = '{}'")
                    .contains("acl_tags && ");
        }
        assertThat(hnswPlan).contains("Index Scan using chunks_embedding_hnsw_idx");
        assertThat(matchingPlan).as("全文条件同样在扫描节点上过滤").containsPattern("Filter: \\(\\(tsv @@ ");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery("SHOW hnsw.iterative_scan")) {
            rs.next();
            assertThat(rs.getString(1)).as("应用数据源的连接开启了迭代扫描").isEqualTo("strict_order");
        }
    }

    @Test
    void publicSearchReturnsSourcesAndOnlyChunksTheCallerMaySee() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        ingest(tenant, base, "travel-policy.md", evalCorpus("travel-policy.md"), null);
        ingest(tenant, base, "compensation.md", evalCorpus("compensation.md"), "level:confidential,role:hr");

        JsonNode anonymous = search(tenant, base, "{\"query\":\"年终奖发几个月\",\"top_k\":50}");
        assertThat(filenames(anonymous)).hasSize(5).containsOnly("travel-policy.md");

        JsonNode hr = search(tenant, base, "{\"query\":\"年终奖发几个月\",\"top_k\":50,\"acl_tags\":[\"role:hr\"]}");
        assertThat(filenames(hr)).hasSize(10).contains("travel-policy.md", "compensation.md");
        JsonNode bonus = hit(hr, "第四条 年终奖");
        assertThat(bonus.get("filename").asString()).isEqualTo("compensation.md");
        assertThat(bonus.get("content").asString()).contains("2 个月基本工资");
        assertThat(bonus.get("source").get("heading_path").get(0).asString()).isEqualTo("薪酬与绩效管理办法");
        assertThat(bonus.get("source").get("page_start").isNull()).isTrue();
        assertThat(bonus.get("kind").asString()).isEqualTo("text");
        assertThat(bonus.get("chunk_id").asString()).startsWith("chk_");

        JsonNode limited = search(tenant, base, "{\"query\":\"住宿\",\"top_k\":3}");
        assertThat(limited.get("data")).hasSize(3);
        JsonNode defaults = search(tenant, base, "{\"query\":\"住宿\"}");
        assertThat(defaults.get("data")).as("默认 top_k = 8，只有 5 个可见切片").hasSize(5);

        Tenant other = newTenant();
        assertThat(client().api(
                                "POST /v1/knowledge-bases/{knowledge_base_id}/search",
                                "POST",
                                "/v1/knowledge-bases/" + base + "/search",
                                other.key(),
                                "{\"query\":\"住宿\"}",
                                Map.of())
                        .status())
                .as("别的项目的知识库与不存在相同")
                .isEqualTo(404);
        assertThat(client().api(
                                "POST /v1/knowledge-bases/{knowledge_base_id}/search",
                                "POST",
                                "/v1/knowledge-bases/" + base + "/search",
                                tenant.key(),
                                "{\"query\":\"   \"}",
                                Map.of())
                        .status())
                .isEqualTo(400);
        String readOnly = PLATFORM.issue(tenant.tenantId(), tenant.projectId(), "knowledge:read");
        assertThat(client().api(
                                "POST /v1/knowledge-bases/{knowledge_base_id}/search",
                                "POST",
                                "/v1/knowledge-bases/" + base + "/search",
                                readOnly,
                                "{\"query\":\"住宿\"}",
                                Map.of())
                        .status())
                .as("检索只要读权限")
                .isEqualTo(200);
    }

    @Test
    void rerankReordersTheFusedCandidatesWhenTheKnowledgeBaseHasAModel() {
        Tenant tenant = newTenant();
        String reranked = createKnowledgeBase(tenant, "mdl_test_rerank");
        String plain = createKnowledgeBase(tenant);
        for (String base : List.of(reranked, plain)) {
            ingest(tenant, base, "attendance.md", evalCorpus("attendance.md"), null);
        }
        int before = PLATFORM.rerankCalls().size();

        JsonNode withRerank = search(tenant, reranked, "{\"query\":\"法定节假日加班工资\",\"top_k\":2}");
        List<StubRerank> calls = PLATFORM
                .rerankCalls()
                .subList(before, PLATFORM.rerankCalls().size())
                .stream()
                .map(call -> new StubRerank(
                        call.model(), call.query(), call.documents().size()))
                .toList();
        assertThat(calls).containsExactly(new StubRerank("mdl_test_rerank", "法定节假日加班工资", 5));
        assertThat(withRerank.get("data")).hasSize(2);
        JsonNode best = withRerank.get("data").get(0);
        assertThat(best.get("source").get("heading_path").get(1).asString()).isEqualTo("第四条 加班与调休");
        assertThat(best.get("score").asDouble())
                .as("重排后的分数是 rerank 模型的得分")
                .isEqualTo(ScriptedVectors.overlap(
                        "法定节假日加班工资",
                        "考勤与休假制度 / 第四条 加班与调休\n" + best.get("content").asString()));

        int afterFirst = PLATFORM.rerankCalls().size();
        JsonNode withoutRerank = search(tenant, plain, "{\"query\":\"法定节假日加班工资\",\"top_k\":2}");
        assertThat(PLATFORM.rerankCalls()).hasSize(afterFirst);
        assertThat(withoutRerank.get("data").get(0).get("score").asDouble())
                .as("不重排时是 RRF 分，不超过两路第一名之和 2/61")
                .isLessThanOrEqualTo(2.0 / 61);
    }

    @Test
    void internalSearchServesRuntimeWithSessionTagsRewriteAndMetering() {
        Tenant tenant = newTenant();
        String first = createKnowledgeBase(tenant);
        String second = createKnowledgeBase(tenant);
        ingest(tenant, first, "reimbursement.md", evalCorpus("reimbursement.md"), "dept:finance");
        ingest(tenant, second, "travel-policy.md", evalCorpus("travel-policy.md"), null);
        String runtime = PLATFORM.issuer().serviceToken(ServiceJwts.RUNTIME, ServiceJwts.KNOWLEDGE);

        PLATFORM.chat(question -> "\"报销 审批 流程\"\n");
        ApiClient.Reply reply = client().internal(
                        "POST",
                        "/internal/v1/knowledge-bases:search",
                        runtime,
                        internalRequest(tenant, List.of(first, second), List.of(), "mdl_test_chat"));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("query").asString()).as("改写后的查询（去掉引号与换行）").isEqualTo("报销 审批 流程");
        assertThat(filenames(reply.json()))
                .as("会话没有 dept:finance，报销制度一条都不出现")
                .isNotEmpty()
                .containsOnly("travel-policy.md");
        assertThat(PLATFORM.chatCalls().getLast().messages().getLast()).isEqualTo("一笔两万块钱的报销需要谁审批？");
        assertThat(PLATFORM.chatCalls().getLast().model()).isEqualTo("mdl_test_chat");

        PLATFORM.chat(null);
        ApiClient.Reply finance = client().internal(
                        "POST",
                        "/internal/v1/knowledge-bases:search",
                        runtime,
                        internalRequest(tenant, List.of(first, second), List.of("dept:finance"), "mdl_missing_chat"));
        assertThat(finance.status()).isEqualTo(200);
        assertThat(finance.json().get("query").asString()).as("改写失败时用原查询").isEqualTo("一笔两万块钱的报销需要谁审批？");
        assertThat(filenames(finance.json())).contains("reimbursement.md");
        assertThat(finance.json().get("data").get(0).get("heading_path").isArray())
                .isTrue();

        Tenant other = newTenant();
        String foreign = createKnowledgeBase(other);
        assertThat(client().internal(
                                "POST",
                                "/internal/v1/knowledge-bases:search",
                                runtime,
                                internalRequest(tenant, List.of(first, foreign), List.of(), null))
                        .status())
                .as("知识库必须属于请求的项目")
                .isEqualTo(404);
        assertThat(client().internal(
                                "POST",
                                "/internal/v1/knowledge-bases:search",
                                PLATFORM.issuer().serviceToken(ServiceJwts.CONTROL_PLANE, ServiceJwts.KNOWLEDGE),
                                internalRequest(tenant, List.of(first), List.of(), null))
                        .status())
                .as("只接受 runtime")
                .isEqualTo(403);

        List<String> usage = db().queryForList(
                        "SELECT payload::text FROM kb.outbox WHERE topic = 'usage' AND payload->>'kind' = 'knowledge_search_calls'"
                                + " AND payload->>'tenant_id' = ? ORDER BY id",
                        String.class,
                        tenant.tenantId());
        assertThat(usage).hasSize(2);
        JsonNode message = cn.refinex.meridian.common.json.JsonMappers.lenient().readTree(usage.getFirst());
        assertThat(message.get("source").asString()).isEqualTo("agent");
        assertThat(message.get("session_id").asString()).isEqualTo("ses_search_test");
        assertThat(message.get("turn_id").asString()).isEqualTo("trn_search_test");
        assertThat(message.get("api_key_id").asString()).isEqualTo("key_search_test");
        assertThat(message.get("project_id").asString()).isEqualTo(tenant.projectId());
        assertThat(message.get("count").asInt()).isEqualTo(1);
        assertThat(message.get("knowledge_base_ids")).hasSize(2);
        assertThat(message.propertyNames())
                .as("字段与契约样本一致（jsonb 不保留键顺序）")
                .containsExactlyInAnyOrderElementsOf(cn.refinex.meridian.common.json.JsonMappers.lenient()
                        .readTree(cn.refinex.meridian.testsupport.outbox.OutboxSamples.read(
                                "usage", "knowledge_search_calls"))
                        .propertyNames());
    }

    private record StubRerank(String model, String query, int documents) {}

    private static String internalRequest(Tenant tenant, List<String> bases, List<String> tags, String rewriteModel) {
        StringBuilder json = new StringBuilder()
                .append("{\"tenant_id\":\"")
                .append(tenant.tenantId())
                .append("\",\"project_id\":\"")
                .append(tenant.projectId())
                .append("\",\"knowledge_base_ids\":")
                .append(jsonArray(bases))
                .append(",\"query\":\"一笔两万块钱的报销需要谁审批？\",\"top_k\":10,\"acl_tags\":")
                .append(jsonArray(tags))
                .append(",\"session_id\":\"ses_search_test\",\"turn_id\":\"trn_search_test\"")
                .append(",\"api_key_id\":\"key_search_test\"");
        if (rewriteModel != null) {
            json.append(",\"rewrite_model\":\"").append(rewriteModel).append('"');
        }
        return json.append('}').toString();
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(v -> "\"" + v + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(v -> "[" + v + "]")
                .orElse("[]");
    }

    private JsonNode search(Tenant tenant, String base, String body) {
        ApiClient.Reply reply = client().api(
                        "POST /v1/knowledge-bases/{knowledge_base_id}/search",
                        "POST",
                        "/v1/knowledge-bases/" + base + "/search",
                        tenant.key(),
                        body,
                        Map.of());
        if (reply.status() != 200) {
            throw new AssertionError(reply.raw());
        }
        return reply.json();
    }

    private static JsonNode hit(JsonNode result, String clause) {
        for (JsonNode hit : result.get("data")) {
            if (hit.get("source").get("heading_path").get(1).asString().equals(clause)) {
                return hit;
            }
        }
        throw new AssertionError("结果中没有 " + clause + "：" + result);
    }

    private static List<String> filenames(JsonNode result) {
        List<String> names = new ArrayList<>();
        result.get("data").forEach(hit -> names.add(hit.get("filename").asString()));
        return names;
    }

    static byte[] evalCorpus(String name) {
        try {
            return Files.readAllBytes(EVAL_CORPUS.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String bind(String sql, Tenant tenant, String base) {
        return sql.replace(":tenant_id", "'" + tenant.tenantId() + "'")
                .replace(":project_id", "'" + tenant.projectId() + "'")
                .replace(":knowledge_base_ids", "'{" + base + "}'")
                .replace(":caller_tags", "'{dept:finance}'");
    }

    private static String explain(String sql, String... settings) {
        SingleConnectionDataSource connection = null;
        try (var source = db().getDataSource().getConnection()) {
            connection = new SingleConnectionDataSource(source, true);
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(connection));
            return tx.execute(status -> {
                // 测试数据量小，规划器本会选择顺序扫描：关掉它，看语句能用上哪些索引
                jdbc.execute("SET LOCAL enable_seqscan = off");
                for (String setting : settings) {
                    jdbc.execute(setting);
                }
                return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
            });
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            if (connection != null) {
                connection.destroy();
            }
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
