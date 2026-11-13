package cn.refinex.meridian.controlplane.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import cn.refinex.meridian.controlplane.metering.domain.CostCalculator;
import cn.refinex.meridian.controlplane.metering.domain.MeteringErrorCode;
import cn.refinex.meridian.controlplane.metering.domain.UsageDimension;
import cn.refinex.meridian.controlplane.metering.domain.UsageKind;
import cn.refinex.meridian.controlplane.metering.domain.UsageMessages;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 用量消息的解析与成本计算（T16，ADR-0048 第 3 条）：消息取 runtime 的契约样本。 */
class MeteringDomainTest {

    private static final TokenPrices PRICES = new TokenPrices(
            new BigDecimal("0.8"),
            new BigDecimal("2"),
            new BigDecimal("0.16"),
            new BigDecimal("0.8"),
            new BigDecimal("8"));

    @Test
    void modelUsageIsParsedAndPricedBySubsets() {
        UsageRecord record = UsageMessages.parse("msg_1", OutboxSamples.read("usage", "model_tokens"));

        assertThat(record.kind()).isEqualTo(UsageKind.MODEL_TOKENS);
        assertThat(record.occurredAt()).isEqualTo(Instant.parse("2026-11-12T08:00:00Z"));
        assertThat(record.priceBookId()).isEqualTo("prb_sample");
        assertThat(record.priceBookVersion()).isEqualTo(1);
        assertThat(record.quantity()).isEqualByComparingTo("1500");
        assertThat(record.billableTokens()).isEqualTo(1500);
        // 900×0.8 + 200×0.16 + 100×0.8 + 250×2 + 50×8 = 1732（每百万 token）
        BigDecimal cost = CostCalculator.cost(record, PRICES);
        assertThat(cost).isEqualByComparingTo("0.001732");
        assertThat(cost.scale()).isEqualTo(CostCalculator.SCALE);
        UsageRecord priced = record.priced("agt_1", "CNY", cost);
        assertThat(priced.agentId()).isEqualTo("agt_1");
        assertThat(priced.currency()).isEqualTo("CNY");
        assertThat(priced.cost()).isEqualTo(cost);
    }

    @Test
    void subsetsLargerThanTheTotalNeverProduceNegativeCosts() {
        UsageRecord record = UsageMessages.parse("msg_2", """
                {"kind":"model_tokens","occurred_at":"2026-11-12T08:00:00Z","tenant_id":"ten_1","model_id":"mdl_1",
                 "input_tokens":10,"output_tokens":5,"cache_read_tokens":30,"reasoning_tokens":9}""");

        assertThat(CostCalculator.cost(record, PRICES)).isEqualByComparingTo("0.0000768");
    }

    @Test
    void sandboxSearchStorageAndEmbeddingUsageAreParsed() {
        UsageRecord sandbox = UsageMessages.parse("msg_s", OutboxSamples.read("usage", "sandbox_vcpu_seconds"));
        assertThat(sandbox.kind()).isEqualTo(UsageKind.SANDBOX_VCPU_SECONDS);
        assertThat(sandbox.quantity()).isEqualByComparingTo("12.5");
        assertThat(sandbox.billableTokens()).isZero();
        assertThat(sandbox.attributes()).containsEntry("size", "small").containsEntry("vcpu", 1);

        UsageRecord search = UsageMessages.parse("msg_q", OutboxSamples.read("usage", "search_calls"));
        assertThat(search.kind()).isEqualTo(UsageKind.SEARCH_CALLS);
        assertThat(search.quantity()).isEqualByComparingTo("1");
        assertThat(search.attributes()).containsEntry("backend", "searxng");
        assertThat(search.apiKeyId()).isEqualTo("key_sample");

        UsageRecord storage = UsageMessages.parse("msg_k", OutboxSamples.read("usage", "knowledge_storage_gb_days"));
        assertThat(storage.kind()).isEqualTo(UsageKind.KNOWLEDGE_STORAGE_GB_DAYS);
        assertThat(storage.kind().tokens()).isFalse();
        assertThat(storage.quantity()).isEqualByComparingTo("1.75");
        assertThat(storage.billableTokens()).isZero();
        assertThat(storage.projectId()).isEqualTo("prj_sample");
        assertThat(storage.attributes())
                .containsEntry("knowledge_base_id", "kb_sample")
                .containsEntry("day", "2026-11-11")
                .containsEntry("file_bytes", 1500000000L)
                .containsEntry("index_bytes", 250000000L);

        UsageRecord knowledgeSearch =
                UsageMessages.parse("msg_ks", OutboxSamples.read("usage", "knowledge_search_calls"));
        assertThat(knowledgeSearch.kind()).isEqualTo(UsageKind.KNOWLEDGE_SEARCH_CALLS);
        assertThat(knowledgeSearch.kind().tokens()).isFalse();
        assertThat(knowledgeSearch.quantity()).isEqualByComparingTo("1");
        assertThat(knowledgeSearch.billableTokens()).isZero();
        assertThat(knowledgeSearch.sessionId()).isEqualTo("ses_sample");
        assertThat(knowledgeSearch.attributes())
                .containsEntry("source", "agent")
                .containsEntry("knowledge_base_ids", List.of("kb_sample"));

        UsageRecord embedding = UsageMessages.parse("msg_e", OutboxSamples.read("usage", "embedding_tokens"));
        assertThat(embedding.kind()).isEqualTo(UsageKind.EMBEDDING_TOKENS);
        assertThat(embedding.kind().tokens()).isTrue();
        assertThat(CostCalculator.cost(embedding, PRICES)).isEqualByComparingTo("0.000512");
    }

    @Test
    void malformedMessagesAreRejected() {
        assertThatThrownBy(() -> UsageMessages.parse(null, "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageMessages.parse("m", "not json")).hasMessageContaining("JSON");
        assertThatThrownBy(() -> UsageMessages.parse("m", "[1]")).hasMessageContaining("对象");
        assertThatThrownBy(() -> UsageMessages.parse("m", "{\"kind\":\"galaxy\"}"))
                .hasMessageContaining("galaxy");
        assertThatThrownBy(() -> UsageMessages.parse(
                        "m", "{\"kind\":\"model_tokens\",\"occurred_at\":\"yesterday\",\"tenant_id\":\"t\"}"))
                .hasMessageContaining("occurred_at");
        assertThatThrownBy(() -> UsageMessages.parse(
                        "m",
                        "{\"kind\":\"model_tokens\",\"occurred_at\":\"2026-11-12T08:00:00Z\",\"tenant_id\":\"t\","
                                + "\"model_id\":\"m\",\"input_tokens\":-1}"))
                .hasMessageContaining("input_tokens");
        assertThatThrownBy(() -> UsageMessages.parse(
                        "m",
                        "{\"kind\":\"sandbox_vcpu_seconds\",\"occurred_at\":\"2026-11-12T08:00:00Z\",\"tenant_id\":\"t\","
                                + "\"sandbox_id\":\"s\",\"size\":\"small\",\"period_start\":\"2026-11-12T08:00:00Z\","
                                + "\"period_end\":\"2026-11-12T08:00:00Z\",\"vcpu_seconds\":\"x\"}"))
                .hasMessageContaining("vcpu_seconds");
        assertThatThrownBy(() -> UsageMessages.parse(
                        "m", "{\"kind\":\"model_tokens\",\"occurred_at\":\"2026-11-12T08:00:00Z\",\"model_id\":\"m\"}"))
                .hasMessageContaining("tenant_id");
    }

    @Test
    void dimensionsAndKindsRoundTrip() {
        assertThat(UsageDimension.of("api_key")).isEqualTo(UsageDimension.API_KEY);
        assertThat(UsageDimension.API_KEY.value()).isEqualTo("api_key");
        assertThat(UsageKind.of("search_calls").value()).isEqualTo("search_calls");
        assertThat(UsageKind.SANDBOX_VCPU_SECONDS.tokens()).isFalse();
        ErrorCodes.verify(MeteringErrorCode.values());
    }
}
