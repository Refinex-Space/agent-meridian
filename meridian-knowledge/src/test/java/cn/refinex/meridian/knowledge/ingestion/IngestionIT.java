package cn.refinex.meridian.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.knowledge.ingestion.app.IngestionWorker;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.knowledge.support.StubPlatform;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 写入的端到端（T18-A01 至 A06）：四种测试语料经 API 上传，Worker 解析、切分、经 runtime 替身计算向量并入库；进度接口反映状态，失败
 * 按退避重试；doc-parser 是容器化的真实服务（Docling）。
 */
class IngestionIT extends KnowledgeTestBase {

    @Autowired
    private ObjectStore objects;

    @Autowired
    private IngestionWorker worker;

    @Test
    void allFourCorporaAreIngestedWithTheRightParsers() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        Map<String, String> expectedParsers = Map.of(
                "travel-policy.docx", "tika",
                "onboarding.md", "markdown",
                "equipment-rules.pdf", "doc-parser",
                "scanned-notice.pdf", "doc-parser",
                "reimbursement-guide.pdf", "pdfbox");
        Map<String, String> ids = new java.util.LinkedHashMap<>();
        expectedParsers.keySet().forEach(name -> ids.put(name, upload(tenant, base, name, "dept:hr,level:internal")));

        // 刚上传时状态是 queued 或已开始处理
        JsonNode queued = document(tenant, base, ids.get("onboarding.md"));
        assertThat(queued.get("status").asString()).isIn("queued", "processing", "ready");
        assertThat(queued.get("ingestion").get("max_attempts").asInt()).isEqualTo(4);

        for (Map.Entry<String, String> entry : ids.entrySet()) {
            JsonNode document = awaitFinished(tenant, base, entry.getValue());
            assertThat(document.get("status").asString()).as(entry.getKey()).isEqualTo("ready");
            assertThat(document.get("parser").asString())
                    .as(entry.getKey())
                    .isEqualTo(expectedParsers.get(entry.getKey()));
            JsonNode ingestion = document.get("ingestion");
            assertThat(ingestion.get("attempts").asInt()).isEqualTo(1);
            assertThat(ingestion.get("stage").isNull()).isTrue();
            assertThat(ingestion.get("chunks_total").asInt())
                    .isEqualTo(ingestion.get("chunks_embedded").asInt())
                    .isEqualTo(document.get("chunk_count").asInt())
                    .isPositive();
            assertThat(ingestion.get("error").isNull()).isTrue();
            assertThat(ingestion.get("finished_at").isNull()).isFalse();
            List<Map<String, Object>> chunks = chunks(entry.getValue());
            assertThat(chunks).hasSize(document.get("chunk_count").asInt());
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.get("acl_tags")).isEqualTo("dept:hr,level:internal");
                assertThat(chunk.get("dims")).isEqualTo(1024);
            });
        }

        // A03：扫描件经 doc-parser 的 OCR 解析出文字
        String scanned = String.join(
                "",
                chunks(ids.get("scanned-notice.pdf")).stream()
                        .map(c -> (String) c.get("content"))
                        .toList());
        assertThat(scanned).contains("夏季作息时间").contains("十三点三十分");

        // A02：带表格的 PDF——表格整体成为一个切片，带标题路径与页码
        List<Map<String, Object>> equipment = chunks(ids.get("equipment-rules.pdf"));
        assertThat(equipment)
                .filteredOn(chunk -> chunk.get("kind").equals("table"))
                .singleElement()
                .satisfies(table -> {
                    assertThat((String) table.get("content")).contains("研发工程师").contains("行政与财务");
                    JsonNode locator = locator(table);
                    assertThat(locator.get("page_start").asInt()).isEqualTo(1);
                    assertThat(locator.get("heading_path")
                                    .get(locator.get("heading_path").size() - 1)
                                    .asString())
                            .isEqualTo("三、配置标准");
                });
        assertThat(equipment)
                .filteredOn(chunk -> ((String) chunk.get("content")).startsWith("设备达到规定使用年限"))
                .singleElement()
                .satisfies(chunk -> {
                    JsonNode locator = locator(chunk);
                    assertThat(locator.get("page_start").asInt()).isEqualTo(2);
                    assertThat(locator.get("heading_path").toString()).contains("五、报废处置");
                });
        assertThat(equipment)
                .filteredOn(chunk -> chunk.get("kind").equals("text"))
                .allSatisfy(
                        chunk -> assertThat((Integer) chunk.get("token_count")).isBetween(1, 512));

        // A02：制度文件——标题路径与条款编号，表格整体
        List<Map<String, Object>> policy = chunks(ids.get("travel-policy.docx"));
        assertThat(policy)
                .filteredOn(chunk -> chunk.get("kind").equals("table"))
                .singleElement()
                .satisfies(
                        table -> assertThat(locator(table).get("heading_path").toString())
                                .isEqualTo("[\"第三章 费用标准\",\"第二节 住宿费\"]"));
        assertThat(policy).filteredOn(chunk -> chunk.get("kind").equals("text")).allSatisfy(chunk -> {
            assertThat((Integer) chunk.get("token_count")).isBetween(1, 512);
            assertThat(locator(chunk).get("heading_path").size()).isPositive();
            assertThat(locator(chunk).get("page_start").isNull()).isTrue();
        });

        // A04：Embedding 都经 runtime 的内部接口，带 knowledge 的服务 JWT、租户与项目，每批不超过 64 条
        List<StubPlatform.EmbeddingCall> calls = PLATFORM.embeddingCalls().stream()
                .filter(call -> tenant.tenantId().equals(call.tenantId()))
                .toList();
        assertThat(calls).isNotEmpty().allSatisfy(call -> {
            assertThat(call.caller()).isEqualTo("knowledge");
            assertThat(call.projectId()).isEqualTo(tenant.projectId());
            assertThat(call.model()).isEqualTo("mdl_test_embedding");
            assertThat(call.inputs()).isBetween(1, 64);
        });
        // knowledge 自己不产生模型用量：outbox 的 usage 主题里没有这个租户的消息（存储计量只在每日任务中写）
        assertThat(db().queryForObject(
                                "SELECT count(*) FROM kb.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?",
                                Long.class,
                                tenant.tenantId()))
                .isZero();
    }

    @Test
    void largeDocumentsAreEmbeddedInBatchesOf64() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        StringBuilder markdown = new StringBuilder("# 长文档\n\n");
        for (int i = 0; i < 90; i++) {
            markdown.append("## 第").append(i + 1).append("节\n\n第").append(i + 1).append("节的正文，说明一项独立的规定。\n\n");
        }
        String id = client().upload(
                        base,
                        "long.md",
                        markdown.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null,
                        tenant.key(),
                        null)
                .text("id");

        JsonNode document = awaitFinished(tenant, base, id);

        assertThat(document.get("chunk_count").asInt()).isEqualTo(90);
        assertThat(PLATFORM.embeddingCalls().stream()
                        .filter(call -> tenant.tenantId().equals(call.tenantId()))
                        .map(StubPlatform.EmbeddingCall::inputs))
                .containsExactly(64, 26);
    }

    @Test
    void retryableFailuresBackOffAndPermanentFailuresStop() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);

        // 一次 503 之后成功：第 2 次尝试成功
        PLATFORM.failNext(503);
        String retried = upload(tenant, base, "onboarding.md", null);
        JsonNode recovered = awaitFinished(tenant, base, retried);
        assertThat(recovered.get("status").asString()).isEqualTo("ready");
        assertThat(recovered.get("ingestion").get("attempts").asInt()).isEqualTo(2);

        // 模型不存在：不可重试，第 1 次就失败
        PLATFORM.failNext(404);
        String missing = upload(tenant, base, "onboarding.md", null);
        JsonNode failed = awaitFinished(tenant, base, missing);
        assertThat(failed.get("status").asString()).isEqualTo("failed");
        JsonNode ingestion = failed.get("ingestion");
        assertThat(ingestion.get("attempts").asInt()).isEqualTo(1);
        assertThat(ingestion.get("error").get("code").asString()).isEqualTo("model.not_found");
        assertThat(ingestion.get("error").get("retryable").asBoolean()).isFalse();
        assertThat(failed.get("chunk_count").asInt()).isZero();

        // 一直不可用：首次加 3 次重试后失败，间隔 1、2、4 秒（测试配置的基数 1 秒）
        PLATFORM.failNext(503, 503, 503, 503);
        String exhausted = upload(tenant, base, "onboarding.md", null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode waiting = document(tenant, base, exhausted).get("ingestion");
            if (waiting.get("attempts").asInt() < 4) {
                // 等待重试期间给出下一次尝试的时间
                assertThat(waiting.get("next_attempt_at").isNull()).isFalse();
            }
            assertThat(waiting.get("attempts").asInt()).isEqualTo(4);
        });
        JsonNode exhaustedDocument = awaitFinished(tenant, base, exhausted);
        assertThat(exhaustedDocument.get("status").asString()).isEqualTo("failed");
        assertThat(exhaustedDocument
                        .get("ingestion")
                        .get("error")
                        .get("retryable")
                        .asBoolean())
                .isTrue();
        assertThat(exhaustedDocument.get("ingestion").get("next_attempt_at").isNull())
                .isTrue();
        List<Map<String, Object>> attempts = db().queryForList(
                        "SELECT started_at, finished_at FROM kb.ingestion_jobs WHERE document_id = ?", exhausted);
        assertThat(attempts)
                .singleElement()
                .satisfies(job -> assertThat(job.get("finished_at")).isNotNull());
    }

    @Test
    void expiredLeasesAreReclaimedAsAFailedAttempt() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        String id = upload(tenant, base, "onboarding.md", null);
        awaitFinished(tenant, base, id);

        // 模拟崩溃的节点：一个执行中、租约已过期的作业
        db().update("""
                INSERT INTO kb.ingestion_jobs (id, tenant_id, project_id, document_id, status, attempts, max_attempts,
                                               not_before, lease_owner, lease_expires_at, stage, started_at,
                                               created_at, updated_at)
                VALUES ('ijb_crashed_' || ?, ?, ?, ?, 'running', 1, 4, now(), 'dead-node', now() - interval '1 minute',
                        'embedding', now() - interval '10 minutes', now(), now())
                """, id, tenant.tenantId(), tenant.projectId(), id);
        db().update("UPDATE kb.documents SET status = 'processing' WHERE id = ?", id);
        worker.poll();

        JsonNode document = awaitFinished(tenant, base, id);
        assertThat(document.get("status").asString()).isEqualTo("ready");
        assertThat(document.get("ingestion").get("attempts").asInt()).isEqualTo(2);
        assertThat(db().queryForObject(
                                "SELECT error_code FROM kb.ingestion_jobs WHERE id = 'ijb_crashed_' || ?",
                                String.class,
                                id))
                .as("收回时记下的失败原因在成功后清除")
                .isNull();
    }

    @Test
    void deletingADocumentRemovesChunksAndTheOriginalFile() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        String id = upload(tenant, base, "travel-policy.docx", null);
        awaitFinished(tenant, base, id);
        String key = db().queryForObject("SELECT object_key FROM kb.documents WHERE id = ?", String.class, id);
        assertThat(objects.exists(key)).isTrue();
        assertThat(chunks(id)).isNotEmpty();

        assertThat(client().api(
                                "DELETE /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                                "DELETE",
                                "/v1/knowledge-bases/" + base + "/documents/" + id,
                                tenant.key(),
                                null,
                                Map.of())
                        .status())
                .isEqualTo(204);

        assertThat(chunks(id)).isEmpty();
        assertThat(db().queryForObject("SELECT count(*) FROM kb.ingestion_jobs WHERE document_id = ?", Long.class, id))
                .isZero();
        assertThat(objects.exists(key)).isFalse();
        assertThat(client().api(
                                "GET /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                                "GET",
                                "/v1/knowledge-bases/" + base + "/documents/" + id,
                                tenant.key(),
                                null,
                                Map.of())
                        .text("code"))
                .isEqualTo("document.not_found");
    }

    @Test
    void reprocessingReplacesChunksInOneStep() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        String id = upload(tenant, base, "onboarding.md", null);
        awaitFinished(tenant, base, id);
        List<String> before = db().queryForList("SELECT id FROM kb.chunks WHERE document_id = ?", String.class, id);

        var reply = client().api(
                        "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess",
                        "POST",
                        "/v1/knowledge-bases/" + base + "/documents/" + id + ":reprocess",
                        tenant.key(),
                        null,
                        Map.of());
        assertThat(reply.status()).isEqualTo(202);
        JsonNode document = awaitFinished(tenant, base, id);

        List<String> after = db().queryForList("SELECT id FROM kb.chunks WHERE document_id = ?", String.class, id);
        assertThat(document.get("status").asString()).isEqualTo("ready");
        assertThat(after).hasSameSizeAs(before).doesNotContainAnyElementsOf(before);
    }

    private static JsonNode locator(Map<String, Object> chunk) {
        return JsonMappers.lenient().readTree((String) chunk.get("source_locator"));
    }
}
