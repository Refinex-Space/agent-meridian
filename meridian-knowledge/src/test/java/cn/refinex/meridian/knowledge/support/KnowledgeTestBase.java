package cn.refinex.meridian.knowledge.support;

import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.knowledge.KnowledgeApplication;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * knowledge 集成测试的基类：Testcontainers 的 PostgreSQL、Redis、MinIO，{@link StubPlatform} 代替 control-plane（API Key 校验、服务 JWT）
 * 与 runtime（Embedding），容器化的 doc-parser（{@link DocParserContainer}）。全部测试类共用一个 Spring 上下文，用新的租户与项目隔离。
 */
@SpringBootTest(classes = KnowledgeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class KnowledgeTestBase {

    /** control-plane 与 runtime 的替身，JVM 内共用。 */
    protected static final StubPlatform PLATFORM = StubPlatform.start();

    /** 测试语料目录。 */
    protected static final Path CORPUS = Path.of("src/test/resources/corpus");

    /** 等待写入完成的上限：首次构建并启动 doc-parser、加载 Docling 模型之后，单份语料在十几秒内完成。 */
    protected static final Duration INGESTION_WAIT = Duration.ofMinutes(5);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("meridian.security.service-client.control-plane-url", PLATFORM::url);
        registry.add("meridian.security.service-jwt.client-secret", () -> StubPlatform.KNOWLEDGE_SECRET);
        registry.add("meridian.knowledge.runtime.url", PLATFORM::url);
        registry.add("meridian.knowledge.doc-parser.url", DocParserContainer::url);
    }

    @LocalServerPort
    private int port;

    /**
     * 调用 knowledge 的客户端。
     *
     * @return 客户端
     */
    protected ApiClient client() {
        return new ApiClient("http://127.0.0.1:" + port);
    }

    /**
     * 以迁移角色访问数据库（断言与构造数据用）。
     *
     * @return JDBC 模板
     */
    protected static JdbcTemplate db() {
        return new JdbcTemplate(TestDatabase.migratorDataSource());
    }

    /**
     * 新的租户与项目，以及有知识库读写作用域的 API Key。
     *
     * @return 租户、项目与 Key
     */
    protected static Tenant newTenant() {
        String tenant = Ids.newId(IdPrefix.TENANT);
        String project = Ids.newId(IdPrefix.PROJECT);
        return new Tenant(tenant, project, PLATFORM.issue(tenant, project, "knowledge:read", "knowledge:write"));
    }

    /**
     * 创建知识库。
     *
     * @param tenant 租户
     * @return 知识库 ID
     */
    protected String createKnowledgeBase(Tenant tenant) {
        return createKnowledgeBase(tenant, null);
    }

    /**
     * 创建知识库。
     *
     * @param tenant 租户
     * @param rerankModel rerank 模型；可为空，表示检索时不重排
     * @return 知识库 ID
     */
    protected String createKnowledgeBase(Tenant tenant, String rerankModel) {
        String rerank = rerankModel == null ? "" : ",\"rerank_model\":\"" + rerankModel + "\"";
        ApiClient.Reply reply = client().api(
                        "POST /v1/knowledge-bases",
                        "POST",
                        "/v1/knowledge-bases",
                        tenant.key(),
                        "{\"name\":\"公司制度\",\"embedding_model\":\"mdl_test_embedding\"" + rerank + "}",
                        Map.of());
        if (reply.status() != 201) {
            throw new AssertionError(reply.raw());
        }
        return reply.text("id");
    }

    /**
     * 上传语料中的一份文档。
     *
     * @param tenant 租户
     * @param knowledgeBaseId 知识库 ID
     * @param name 语料文件名
     * @param aclTags 标签；可为空
     * @return 文档 ID
     */
    protected String upload(Tenant tenant, String knowledgeBaseId, String name, String aclTags) {
        ApiClient.Reply reply = client().upload(knowledgeBaseId, name, corpus(name), aclTags, tenant.key(), null);
        if (reply.status() != 202) {
            throw new AssertionError(reply.raw());
        }
        return reply.text("id");
    }

    /**
     * 上传一份文档并等它写入完成。
     *
     * @param tenant 租户
     * @param knowledgeBaseId 知识库 ID
     * @param filename 文件名
     * @param content 内容
     * @param aclTags 标签；可为空
     * @return 文档 ID
     */
    protected String ingest(Tenant tenant, String knowledgeBaseId, String filename, byte[] content, String aclTags) {
        ApiClient.Reply reply = client().upload(knowledgeBaseId, filename, content, aclTags, tenant.key(), null);
        if (reply.status() != 202) {
            throw new AssertionError(reply.raw());
        }
        String documentId = reply.text("id");
        JsonNode finished = awaitFinished(tenant, knowledgeBaseId, documentId);
        if (!finished.get("status").asString().equals("ready")) {
            throw new AssertionError("写入失败：" + finished);
        }
        return documentId;
    }

    /**
     * 读取语料。
     *
     * @param name 文件名
     * @return 内容
     */
    protected static byte[] corpus(String name) {
        try {
            return Files.readAllBytes(CORPUS.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 查看文档。
     *
     * @param tenant 租户
     * @param knowledgeBaseId 知识库 ID
     * @param documentId 文档 ID
     * @return 文档的 JSON
     */
    protected JsonNode document(Tenant tenant, String knowledgeBaseId, String documentId) {
        ApiClient.Reply reply = client().api(
                        "GET /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                        "GET",
                        "/v1/knowledge-bases/" + knowledgeBaseId + "/documents/" + documentId,
                        tenant.key(),
                        null,
                        Map.of());
        if (reply.status() != 200) {
            throw new AssertionError(reply.raw());
        }
        return reply.json();
    }

    /**
     * 等待文档的写入结束（{@code ready} 或 {@code failed}）。
     *
     * @param tenant 租户
     * @param knowledgeBaseId 知识库 ID
     * @param documentId 文档 ID
     * @return 结束时的文档
     */
    protected JsonNode awaitFinished(Tenant tenant, String knowledgeBaseId, String documentId) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(INGESTION_WAIT).pollInterval(Duration.ofMillis(300)).until(() -> {
            last[0] = document(tenant, knowledgeBaseId, documentId);
            String status = last[0].get("status").asString();
            return status.equals("ready") || status.equals("failed");
        });
        return last[0];
    }

    /**
     * 文档的切片（按顺序）。
     *
     * @param documentId 文档 ID
     * @return 每行的 kind、content、token_count、acl_tags、source_locator（文本）
     */
    protected static List<Map<String, Object>> chunks(String documentId) {
        return db().queryForList("""
                SELECT kind, content, token_count, array_to_string(acl_tags, ',') AS acl_tags,
                       source_locator::text AS source_locator, vector_dims(embedding) AS dims
                  FROM kb.chunks WHERE document_id = ? ORDER BY seq
                """, documentId);
    }

    /**
     * 测试用的租户。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param key 有 {@code knowledge:read}、{@code knowledge:write} 的 API Key
     */
    protected record Tenant(String tenantId, String projectId, String key) {}
}
