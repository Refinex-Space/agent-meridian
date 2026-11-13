package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.knowledge.search.domain.SearchPlan;
import cn.refinex.meridian.knowledge.support.DashScopeModels;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 检索评测的录制，第二步（T19-A03，{@code -Plive}）：回放 {@code RetrievalEvalEmbeddingsLiveIT} 录制的向量，"混合加 rerank" 对每个
 * 问题的融合候选调 DashScope 的真实 rerank，录制到 {@code retrieval-eval/recording/reranks.json}，并给出四种方式的指标（即离线回归
 * {@code RetrievalEvalIT} 的基线）。20 次调用（每个问题 1 次）。
 */
@Tag("live")
class RetrievalEvalRerankLiveIT extends RetrievalEvaluation {

    @AfterEach
    void restore() {
        PLATFORM.embedder(null);
        PLATFORM.reranker(null);
    }

    @Test
    void recordRerankScoresAndMeasureAllFourConfigurations() {
        DashScopeModels dashscope = DashScopeModels.fromEnvironment();
        assumeTrue(dashscope != null, "没有设置 DASHSCOPE_API_KEY");
        assumeTrue(Files.exists(EMBEDDINGS), "先运行 RetrievalEvalEmbeddingsLiveIT 录制向量");
        replayEmbeddings(readEmbeddings());
        Map<String, Map<String, Double>> recorded = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        PLATFORM.reranker((query, documents) -> {
            calls.incrementAndGet();
            List<Double> scores = dashscope.rerank(query, documents);
            Map<String, Double> byDocument = new LinkedHashMap<>();
            for (int i = 0; i < documents.size(); i++) {
                byDocument.put(documents.get(i), scores.get(i));
            }
            recorded.put(query, byDocument);
            return scores;
        });

        Corpus corpus = ingestCorpus();
        List<Metrics> metrics = new ArrayList<>();
        for (SearchPlan plan : PLANS) {
            metrics.add(evaluate(corpus, plan));
        }
        writeReranks(dashscope.rerankModel(), recorded);
        print(metrics);

        assertThat(calls.get()).as("每个问题一次 rerank").isEqualTo(20);
    }
}
