package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.knowledge.search.domain.SearchPlan;
import cn.refinex.meridian.knowledge.support.DashScopeModels;
import cn.refinex.meridian.knowledge.support.StubPlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 检索评测的录制，第一步（T19-A03，{@code -Plive}）：用 DashScope 的真实 Embedding 写入语料、计算 20 个问题的向量，录制到
 * {@code retrieval-eval/recording/embeddings.json}，并给出不重排的三种方式的指标。约 7 次调用（问题 1 次、6 份文档各 1 次）。
 *
 * <p>rerank 的录制在 {@code RetrievalEvalRerankLiveIT}，它回放本类录制的向量；分成两个类是为了每个类的真实调用不超过 20 次
 * （{@code docs/engineering/testing.md}）。改了语料、问题或切分之后，先运行本类，再运行 rerank 的录制。
 */
@Tag("live")
class RetrievalEvalEmbeddingsLiveIT extends RetrievalEvaluation {

    @AfterEach
    void restore() {
        PLATFORM.embedder(null);
    }

    @Test
    void recordEmbeddingsAndMeasureTheRecallWithoutRerank() {
        DashScopeModels dashscope = DashScopeModels.fromEnvironment();
        assumeTrue(dashscope != null, "没有设置 DASHSCOPE_API_KEY");
        Map<String, float[]> recorded = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> questions = questions().stream().map(Question::question).toList();
        record(dashscope, questions, recorded, calls);
        PLATFORM.embedder(texts -> {
            List<String> missing =
                    texts.stream().filter(text -> !recorded.containsKey(text)).toList();
            record(dashscope, missing, recorded, calls);
            return new StubPlatform.Embedded(texts.stream().map(recorded::get).toList(), 0);
        });

        Corpus corpus = ingestCorpus();
        List<Metrics> metrics = new ArrayList<>();
        for (SearchPlan plan : List.of(SearchPlan.VECTOR_ONLY, SearchPlan.FULL_TEXT_ONLY, SearchPlan.HYBRID)) {
            metrics.add(evaluate(corpus, plan));
        }
        writeEmbeddings(dashscope.embeddingModel(), recorded);
        print(metrics);

        assertThat(recorded).as("30 个切片与 20 个问题").hasSize(50);
        assertThat(calls.get()).as("真实调用次数").isLessThanOrEqualTo(20);
    }

    private static void record(
            DashScopeModels dashscope, List<String> texts, Map<String, float[]> recorded, AtomicInteger calls) {
        for (int from = 0; from < texts.size(); from += DashScopeModels.MAX_EMBEDDING_BATCH) {
            List<String> batch =
                    texts.subList(from, Math.min(texts.size(), from + DashScopeModels.MAX_EMBEDDING_BATCH));
            calls.incrementAndGet();
            List<float[]> vectors = dashscope.embed(batch).vectors();
            for (int i = 0; i < batch.size(); i++) {
                recorded.put(batch.get(i), vectors.get(i));
            }
        }
    }
}
