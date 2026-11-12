package cn.refinex.meridian.knowledge.metering;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.knowledge.metering.app.StorageMetering;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 知识库存储量的每日上报（T18-A09，ADR-0051）：每个有内容的知识库每天一条 outbox {@code usage} 消息，字段与
 * {@code meridian-test-support} 的样本一致（control-plane 的消费者用同一份样本），重复执行不重复上报。
 */
class StorageMeteringIT extends KnowledgeTestBase {

    @Autowired
    private StorageMetering metering;

    @Test
    void eachKnowledgeBaseIsReportedOncePerDay() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        String empty = createKnowledgeBase(tenant);
        awaitFinished(tenant, base, upload(tenant, base, "onboarding.md", null));
        awaitFinished(tenant, base, upload(tenant, base, "travel-policy.docx", null));
        long fileBytes = corpus("onboarding.md").length + corpus("travel-policy.docx").length;
        long indexBytes = db().queryForObject(
                        "SELECT sum(index_bytes) FROM kb.documents WHERE knowledge_base_id = ?", Long.class, base);

        metering.reportYesterday();
        metering.reportYesterday();

        List<JsonNode> messages = messages(tenant);
        assertThat(messages).singleElement().satisfies(message -> {
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            assertThat(message.get("kind").asString()).isEqualTo("knowledge_storage_gb_days");
            assertThat(message.get("knowledge_base_id").asString()).isEqualTo(base);
            assertThat(message.get("project_id").asString()).isEqualTo(tenant.projectId());
            assertThat(message.get("day").asString()).isEqualTo(yesterday.toString());
            assertThat(message.get("occurred_at").asString())
                    .isEqualTo(yesterday
                            .plusDays(1)
                            .atStartOfDay()
                            .toInstant(ZoneOffset.UTC)
                            .toString());
            assertThat(message.get("file_bytes").asLong()).isEqualTo(fileBytes);
            assertThat(message.get("index_bytes").asLong())
                    .isEqualTo(indexBytes)
                    .isPositive();
            assertThat(message.get("gb_days").decimalValue())
                    .isEqualByComparingTo(
                            BigDecimal.valueOf(fileBytes + indexBytes).movePointLeft(9));
            assertThat(fieldNames(message))
                    .containsExactlyElementsOf(fieldNames(
                            JsonMappers.lenient().readTree(OutboxSamples.read("usage", "knowledge_storage_gb_days"))));
        });
        assertThat(db().queryForObject(
                                "SELECT count(*) FROM kb.storage_reports WHERE knowledge_base_id = ?",
                                Long.class,
                                empty))
                .as("没有文档的知识库不上报")
                .isZero();
    }

    private static List<JsonNode> messages(Tenant tenant) {
        return db()
                .queryForList(
                        "SELECT payload::text FROM kb.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?",
                        String.class,
                        tenant.tenantId())
                .stream()
                .map(json -> JsonMappers.lenient().readTree(json))
                .toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>(node.propertyNames());
        names.sort(String::compareTo);
        return names;
    }
}
