package cn.refinex.meridian.knowledge.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.knowledge.support.ApiClient;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 知识库公开 API 与内部接口（T18-A08、A09 与契约）：全部 8 个操作按规范校验；作用域、跨项目隔离与错误码；POST 的
 * {@code Idempotency-Key}；写操作的审计；API Key 经 control-plane 校验、吊销后 1 秒内拒绝；内部接口只接受 control-plane 与 runtime。
 */
class KnowledgeApiIT extends KnowledgeTestBase {

    @Autowired
    private ObjectStore objects;

    private static final String KB = "/v1/knowledge-bases";

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void everyOperationFollowsTheSpecificationAndScopesAreEnforced() {
        Tenant tenant = newTenant();
        ApiClient api = client();
        ApiClient.Reply created = api.api(
                "POST /v1/knowledge-bases",
                "POST",
                KB,
                tenant.key(),
                "{\"name\":\"  人事制度 \",\"description\":\"入职与考勤\",\"embedding_model\":\"mdl_test_embedding\"}",
                Map.of());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("name")).isEqualTo("人事制度");
        assertThat(created.json().get("embedding_dimensions").asInt()).isEqualTo(1024);
        assertThat(created.json().get("document_count").asLong()).isZero();
        String base = created.text("id");

        ApiClient.Reply list = api.api("GET /v1/knowledge-bases", "GET", KB + "?limit=1", tenant.key(), null, Map.of());
        assertThat(list.json().get("data").get(0).get("id").asString()).isEqualTo(base);
        assertThat(api.api(
                                "GET /v1/knowledge-bases/{knowledge_base_id}",
                                "GET",
                                KB + "/" + base,
                                tenant.key(),
                                null,
                                Map.of())
                        .text("embedding_model"))
                .isEqualTo("mdl_test_embedding");

        String document = upload(tenant, base, "onboarding.md", "dept:hr");
        JsonNode documents = api.api(
                        "GET /v1/knowledge-bases/{knowledge_base_id}/documents",
                        "GET",
                        KB + "/" + base + "/documents?limit=10",
                        tenant.key(),
                        null,
                        Map.of())
                .json();
        assertThat(documents.get("data").get(0).get("id").asString()).isEqualTo(document);
        assertThat(documents.get("data").get(0).get("acl_tags").get(0).asString())
                .isEqualTo("dept:hr");
        awaitFinished(tenant, base, document);

        // 正在写入时不能重新处理
        ApiClient.Reply first = api.api(
                "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess",
                "POST",
                KB + "/" + base + "/documents/" + document + ":reprocess",
                tenant.key(),
                null,
                Map.of());
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.text("status")).isIn("queued", "processing", "ready");
        ApiClient.Reply conflict = api.api(
                "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess",
                "POST",
                KB + "/" + base + "/documents/" + document + ":reprocess",
                tenant.key(),
                null,
                Map.of());
        if (conflict.status() == 409) {
            assertThat(conflict.text("code")).isEqualTo("document.processing");
        }
        awaitFinished(tenant, base, document);
        assertThat(api.api(
                                "DELETE /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                                "DELETE",
                                KB + "/" + base + "/documents/" + document,
                                tenant.key(),
                                null,
                                Map.of())
                        .status())
                .isEqualTo(204);

        // 作用域：只读的 Key 不能写，没有知识库作用域的 Key 不能读
        String readOnly = PLATFORM.issue(tenant.tenantId(), tenant.projectId(), "knowledge:read");
        String sessionsOnly = PLATFORM.issue(tenant.tenantId(), tenant.projectId(), "sessions:read");
        ApiClient.Reply denied = api.api(
                "POST /v1/knowledge-bases",
                "POST",
                KB,
                readOnly,
                "{\"name\":\"x\",\"embedding_model\":\"m\"}",
                Map.of());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.text("code")).isEqualTo("auth.insufficient_scope");
        assertThat(api.api("GET /v1/knowledge-bases", "GET", KB, readOnly, null, Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(api.api("GET /v1/knowledge-bases", "GET", KB, sessionsOnly, null, Map.of())
                        .status())
                .isEqualTo(403);

        // 其他项目的知识库与不存在的相同
        Tenant other = newTenant();
        ApiClient.Reply hidden = api.api(
                "GET /v1/knowledge-bases/{knowledge_base_id}", "GET", KB + "/" + base, other.key(), null, Map.of());
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.text("code")).isEqualTo("knowledge_base.not_found");
        ApiClient.Reply missingDocument = api.api(
                "GET /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                "GET",
                KB + "/" + base + "/documents/" + Ids.newId(IdPrefix.DOCUMENT),
                tenant.key(),
                null,
                Map.of());
        assertThat(missingDocument.text("code")).isEqualTo("document.not_found");

        assertThat(ApiClient.validatedOperations())
                .containsAll(List.of(
                        "POST /v1/knowledge-bases",
                        "GET /v1/knowledge-bases",
                        "GET /v1/knowledge-bases/{knowledge_base_id}",
                        "POST /v1/knowledge-bases/{knowledge_base_id}/documents",
                        "GET /v1/knowledge-bases/{knowledge_base_id}/documents",
                        "GET /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                        "DELETE /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                        "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess"));
        // 修改与删除知识库在 knowledgeBasesCanBeUpdatedAndDeletedWithEverythingInThem 中校验
    }

    @Test
    void knowledgeBasesCanBeUpdatedAndDeletedWithEverythingInThem() {
        Tenant tenant = newTenant();
        ApiClient api = client();
        String base = createKnowledgeBase(tenant);
        String path = KB + "/" + base;

        ApiClient.Reply renamed = api.api(
                "PATCH /v1/knowledge-bases/{knowledge_base_id}",
                "PATCH",
                path,
                tenant.key(),
                "{\"name\":\" 新名字 \",\"description\":\"\",\"rerank_model\":\"mdl_test_rerank\"}",
                Map.of());
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.text("name")).isEqualTo("新名字");
        assertThat(renamed.json().get("description").isNull()).isTrue();
        assertThat(renamed.text("rerank_model")).isEqualTo("mdl_test_rerank");
        ApiClient.Reply cleared = api.api(
                "PATCH /v1/knowledge-bases/{knowledge_base_id}",
                "PATCH",
                path,
                tenant.key(),
                "{\"rerank_model\":\"\"}",
                Map.of());
        assertThat(cleared.text("name")).as("没给出的字段不变").isEqualTo("新名字");
        assertThat(cleared.json().get("rerank_model").isNull()).isTrue();

        String document = upload(tenant, base, "onboarding.md", "dept:hr");
        awaitFinished(tenant, base, document);
        String objectKey =
                db().queryForObject("SELECT object_key FROM kb.documents WHERE id = ?", String.class, document);
        assertThat(objects.exists(objectKey)).isTrue();
        assertThat(chunks(document)).isNotEmpty();

        String readOnly = PLATFORM.issue(tenant.tenantId(), tenant.projectId(), "knowledge:read");
        assertThat(api.api("DELETE /v1/knowledge-bases/{knowledge_base_id}", "DELETE", path, readOnly, null, Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(api.api(
                                "DELETE /v1/knowledge-bases/{knowledge_base_id}",
                                "DELETE",
                                path,
                                newTenant().key(),
                                null,
                                Map.of())
                        .status())
                .as("其他项目的知识库与不存在相同")
                .isEqualTo(404);
        assertThat(api.api(
                                "DELETE /v1/knowledge-bases/{knowledge_base_id}",
                                "DELETE",
                                path,
                                tenant.key(),
                                null,
                                Map.of())
                        .status())
                .isEqualTo(204);

        assertThat(api.api("GET /v1/knowledge-bases/{knowledge_base_id}", "GET", path, tenant.key(), null, Map.of())
                        .text("code"))
                .isEqualTo("knowledge_base.not_found");
        for (String table : List.of("documents", "storage_reports")) {
            assertThat(db().queryForObject(
                                    "SELECT count(*) FROM kb." + table + " WHERE knowledge_base_id = ?",
                                    Long.class,
                                    base))
                    .as(table)
                    .isZero();
        }
        assertThat(chunks(document)).isEmpty();
        assertThat(db().queryForObject(
                                "SELECT count(*) FROM kb.ingestion_jobs WHERE document_id = ?", Long.class, document))
                .isZero();
        assertThat(objects.exists(objectKey)).as("原文件随之删除").isFalse();
        assertThat(db().queryForList(
                                "SELECT payload->>'action' FROM kb.outbox WHERE topic = 'audit' AND payload->>'resource_id' = ?"
                                        + " ORDER BY id",
                                String.class,
                                base))
                .containsExactly(
                        "knowledge_base.created",
                        "knowledge_base.updated",
                        "knowledge_base.updated",
                        "knowledge_base.deleted");
        assertThat(api.api(
                                "DELETE /v1/knowledge-bases/{knowledge_base_id}",
                                "DELETE",
                                path,
                                tenant.key(),
                                null,
                                Map.of())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void uploadsAreValidatedByContent() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        ApiClient api = client();

        ApiClient.Reply zip =
                api.upload(base, "a.zip", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}, null, tenant.key(), null);
        assertThat(zip.status()).isEqualTo(400);
        assertThat(zip.text("code")).isEqualTo("document.unsupported_type");
        ApiClient.Reply empty = api.upload(base, "a.md", new byte[0], null, tenant.key(), null);
        assertThat(empty.status()).isEqualTo(400);
        ApiClient.Reply badTags =
                api.upload(base, "a.md", "# x".getBytes(StandardCharsets.UTF_8), "a,,b", tenant.key(), null);
        assertThat(badTags.status()).isEqualTo(400);
        assertThat(badTags.text("code")).isEqualTo("request.validation_failed");
        ApiClient.Reply noBase = api.upload(
                Ids.newId(IdPrefix.KNOWLEDGE_BASE),
                "a.md",
                "# x".getBytes(StandardCharsets.UTF_8),
                null,
                tenant.key(),
                null);
        assertThat(noBase.status()).isEqualTo(404);
        // 客户端声明的类型与扩展名不可信：内容是 PDF 的 .txt 按 PDF 处理
        ApiClient.Reply disguised =
                api.upload(base, "notes.txt", corpus("reimbursement-guide.pdf"), null, tenant.key(), null);
        assertThat(disguised.status()).isEqualTo(202);
        assertThat(disguised.text("media_type")).isEqualTo("application/pdf");
    }

    @Test
    void postsAreIdempotentAndWritesAreAudited() {
        Tenant tenant = newTenant();
        ApiClient api = client();
        Map<String, String> key = Map.of("Idempotency-Key", "create-1");
        String body = "{\"name\":\"财务制度\",\"embedding_model\":\"mdl_test_embedding\"}";

        ApiClient.Reply first = api.api("POST /v1/knowledge-bases", "POST", KB, tenant.key(), body, key);
        ApiClient.Reply replay = api.api("POST /v1/knowledge-bases", "POST", KB, tenant.key(), body, key);
        ApiClient.Reply reused = api.api(
                "POST /v1/knowledge-bases",
                "POST",
                KB,
                tenant.key(),
                "{\"name\":\"别的\",\"embedding_model\":\"mdl_test_embedding\"}",
                key);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.text("id")).isEqualTo(first.text("id"));
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.text("code")).isEqualTo("idempotency.key_reused");
        String base = first.text("id");

        byte[] content = corpus("onboarding.md");
        ApiClient.Reply uploaded = api.upload(base, "onboarding.md", content, "dept:hr", tenant.key(), "upload-1");
        ApiClient.Reply uploadReplay = api.upload(base, "onboarding.md", content, "dept:hr", tenant.key(), "upload-1");
        ApiClient.Reply uploadReused =
                api.upload(base, "onboarding.md", corpus("travel-policy.docx"), "dept:hr", tenant.key(), "upload-1");
        assertThat(uploadReplay.text("id")).isEqualTo(uploaded.text("id"));
        assertThat(uploadReplay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(uploadReused.status()).isEqualTo(409);
        assertThat(db().queryForObject(
                                "SELECT count(*) FROM kb.documents WHERE knowledge_base_id = ?", Long.class, base))
                .isEqualTo(1);
        String document = uploaded.text("id");
        awaitFinished(tenant, base, document);
        api.api(
                "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess",
                "POST",
                KB + "/" + base + "/documents/" + document + ":reprocess",
                tenant.key(),
                null,
                Map.of());
        awaitFinished(tenant, base, document);
        api.api(
                "DELETE /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
                "DELETE",
                KB + "/" + base + "/documents/" + document,
                tenant.key(),
                null,
                Map.of());

        List<JsonNode> audits = db()
                .queryForList(
                        "SELECT payload::text FROM kb.outbox WHERE topic = 'audit' AND payload->>'tenant_id' = ? ORDER BY id",
                        String.class,
                        tenant.tenantId())
                .stream()
                .map(json -> JsonMappers.lenient().readTree(json))
                .toList();
        assertThat(audits)
                .extracting(audit -> audit.get("action").asString())
                .containsExactly(
                        "knowledge_base.created", "document.uploaded", "document.reprocessed", "document.deleted");
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.get("project_id").asString()).isEqualTo(tenant.projectId());
            assertThat(audit.get("actor").get("type").asString()).isEqualTo("api_key");
            assertThat(audit.get("actor").get("subject").asString()).isEqualTo(PLATFORM.keyId(tenant.key()));
            assertThat(audit.has("occurred_at")).isTrue();
        });
        assertThat(audits.get(1).get("resource_id").asString()).isEqualTo(document);
        assertThat(audits.get(1).get("details").get("filename").asString()).isEqualTo("onboarding.md");
    }

    @Test
    void aRevokedKeyIsRejectedWithinOneSecond() {
        Tenant tenant = newTenant();
        ApiClient api = client();
        assertThat(api.api("GET /v1/knowledge-bases", "GET", KB, tenant.key(), null, Map.of())
                        .status())
                .isEqualTo(200);
        int verifications = PLATFORM.verifyCalls();

        PLATFORM.revoke(tenant.key());
        // 吊销通知到达之前缓存仍然接受：证明下面的拒绝来自通知，而不是缓存未命中
        assertThat(api.api("GET /v1/knowledge-bases", "GET", KB, tenant.key(), null, Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(PLATFORM.verifyCalls()).isEqualTo(verifications);

        long published = System.nanoTime();
        await().atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    redis.convertAndSend(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, PLATFORM.keyId(tenant.key()));
                    ApiClient.Reply rejected =
                            api.api("GET /v1/knowledge-bases", "GET", KB, tenant.key(), null, Map.of());
                    assertThat(rejected.status()).isEqualTo(401);
                    assertThat(rejected.text("code")).isEqualTo("auth.invalid_api_key");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - published)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void theInternalLookupAcceptsOnlyControlPlaneAndRuntime() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        String path = "/internal/v1/knowledge-bases/" + base;
        ApiClient api = client();

        ApiClient.Reply found = api.internal(
                "GET", path, PLATFORM.issuer().serviceToken(ServiceJwts.CONTROL_PLANE, ServiceJwts.KNOWLEDGE));
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.text("tenant_id")).isEqualTo(tenant.tenantId());
        assertThat(found.text("project_id")).isEqualTo(tenant.projectId());
        assertThat(found.text("embedding_model")).isEqualTo("mdl_test_embedding");
        assertThat(api.internal("GET", path, PLATFORM.issuer().serviceToken(ServiceJwts.RUNTIME, ServiceJwts.KNOWLEDGE))
                        .status())
                .isEqualTo(200);
        assertThat(api.internal(
                                "GET",
                                path,
                                PLATFORM.issuer().serviceToken(ServiceJwts.EGRESS_PROXY, ServiceJwts.KNOWLEDGE))
                        .status())
                .isEqualTo(403);
        assertThat(api.internal(
                                "GET",
                                path,
                                PLATFORM.issuer().serviceToken(ServiceJwts.CONTROL_PLANE, ServiceJwts.RUNTIME))
                        .status())
                .as("aud 不是 knowledge")
                .isEqualTo(401);
        assertThat(api.internal("GET", path, null).status()).isEqualTo(401);
        ApiClient.Reply missing = api.internal(
                "GET",
                "/internal/v1/knowledge-bases/" + Ids.newId(IdPrefix.KNOWLEDGE_BASE),
                PLATFORM.issuer().serviceToken(ServiceJwts.CONTROL_PLANE, ServiceJwts.KNOWLEDGE));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("code")).isEqualTo("knowledge_base.not_found");
    }
}
