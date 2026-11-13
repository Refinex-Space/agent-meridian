package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.knowledge.search.domain.SearchPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 检索评测的离线回归（T19-A03）：回放录制的 DashScope Embedding 与 rerank 得分（{@code retrieval-eval/recording/}），确定地重算四种
 * 检索方式的命中率@5 与 MRR，低于基线时失败。
 *
 * <p>基线来源：2026-11-12 的 {@code RetrievalEvalRerankLiveIT}（{@code qwen3.7-text-embedding}、{@code qwen3.7-text-rerank}，检索参数取
 * {@code meridian.knowledge.search} 的默认值），记录在 {@code docs/engineering/retrieval-evaluation.md}。改了检索实现之后指标上升，
 * 更新基线并在评测记录中写明；下降就是回归。
 */
class RetrievalEvalIT extends RetrievalEvaluation {

    /** 方式 → {命中率@5, MRR} 的基线。 */
    static final Map<String, double[]> BASELINE = Map.of(
            "vector", new double[] {1.00, 0.9750},
            "full_text", new double[] {0.95, 0.7250},
            "hybrid", new double[] {1.00, 0.9250},
            "hybrid+rerank", new double[] {1.00, 1.0000});

    @AfterEach
    void restore() {
        PLATFORM.embedder(null);
        PLATFORM.reranker(null);
    }

    @Test
    void theFourConfigurationsDoNotFallBelowTheRecordedBaseline() {
        replayEmbeddings(readEmbeddings());
        Map<String, Map<String, Double>> reranks = readReranks();
        PLATFORM.reranker((query, documents) -> {
            Map<String, Double> byDocument = reranks.get(query);
            if (byDocument == null || !byDocument.keySet().containsAll(documents)) {
                throw new IllegalStateException("录制中没有这次 rerank 的候选，需要重新录制：" + query);
            }
            return documents.stream().map(byDocument::get).toList();
        });

        Corpus corpus = ingestCorpus();
        List<Metrics> metrics = new ArrayList<>();
        for (SearchPlan plan : PLANS) {
            metrics.add(evaluate(corpus, plan));
        }
        print(metrics);

        for (Metrics m : metrics) {
            double[] baseline = BASELINE.get(m.plan().label());
            assertThat(m.hitAt5()).as(m.plan().label() + " 命中率@5").isGreaterThanOrEqualTo(baseline[0] - 1e-9);
            assertThat(m.mrr()).as(m.plan().label() + " MRR").isGreaterThanOrEqualTo(baseline[1] - 1e-9);
        }
    }
}
