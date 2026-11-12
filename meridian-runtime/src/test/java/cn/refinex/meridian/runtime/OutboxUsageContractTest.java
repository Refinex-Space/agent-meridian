package cn.refinex.meridian.runtime;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelUsageMessage;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxUsageMessage;
import cn.refinex.meridian.runtime.support.OutboxContract;
import cn.refinex.meridian.runtime.tooling.domain.SearchUsageMessage;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** runtime 写出的用量消息与契约样本一致（T16，ADR-0048）：control-plane 的用量消费者按同一份样本解析。 */
class OutboxUsageContractTest {

    private static final Instant AT = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void modelAndEmbeddingUsageMatchTheSamples() {
        OutboxContract.assertMatches(
                "usage",
                "model_tokens",
                new ModelUsageMessage(
                        ModelUsageMessage.KIND_MODEL_TOKENS,
                        "req_sample",
                        AT,
                        "ten_sample",
                        "prj_sample",
                        "key_sample",
                        "agv_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "mdl_sample",
                        "pac_sample",
                        "qwen-plus",
                        "reasoning",
                        false,
                        "provider",
                        1200,
                        300,
                        200,
                        100,
                        50,
                        "prb_sample",
                        1));
        OutboxContract.assertMatches(
                "usage",
                "embedding_tokens",
                new ModelUsageMessage(
                        ModelUsageMessage.KIND_EMBEDDING_TOKENS,
                        "req_embed",
                        AT,
                        "ten_sample",
                        "prj_sample",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "mdl_embed",
                        "pac_sample",
                        "text-embedding-v4",
                        "embedding",
                        false,
                        "provider",
                        640,
                        0,
                        0,
                        0,
                        0,
                        "prb_sample",
                        1));
    }

    @Test
    void sandboxAndSearchUsageMatchTheSamples() {
        OutboxContract.assertMatches(
                "usage",
                "sandbox_vcpu_seconds",
                new SandboxUsageMessage(
                        SandboxUsageMessage.KIND,
                        AT,
                        "ten_sample",
                        "prj_sample",
                        "agv_sample",
                        "ses_sample",
                        "thr_sample",
                        "trn_sample",
                        "sbx-sample",
                        "small",
                        1,
                        Instant.parse("2026-11-12T07:59:47.500Z"),
                        AT,
                        new BigDecimal("12.500")));
        OutboxContract.assertMatches(
                "usage",
                "search_calls",
                SearchUsageMessage.of(
                        PlatformContext.builder()
                                .tenantId("ten_sample")
                                .projectId("prj_sample")
                                .apiKeyId("key_sample")
                                .sessionId("ses_sample")
                                .threadId("thr_sample")
                                .turnId("trn_sample")
                                .build(),
                        "searxng",
                        AT));
    }
}
