package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.knowledge.search.app.KnowledgeSearchService;
import cn.refinex.meridian.knowledge.search.app.SearchOutcome;
import cn.refinex.meridian.knowledge.search.app.SearchRequest;
import cn.refinex.meridian.knowledge.search.domain.Candidate;
import cn.refinex.meridian.knowledge.search.domain.SearchPlan;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.knowledge.support.StubPlatform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 检索评测（T19-A03）的公共部分：评测集（{@code retrieval-eval/eval-set.json}，6 份制度、30 条条款、20 个问题，写定后不改）、写入语料、
 * 四种检索方式的命中率@5 与 MRR，以及录制文件（{@code retrieval-eval/recording/}）的读写。指标的定义与结果见
 * {@code docs/engineering/retrieval-evaluation.md}。
 *
 * <p>每个问题检索前 {@value #TOP_K} 个切片，标准答案是文件名与条款标题（标题路径的第二级）都相同的那个切片：命中率@5 是它出现在前 5
 * 的问题比例，MRR 是它的排名倒数的平均（前 {@value #TOP_K} 中没有时计 0）。
 */
abstract class RetrievalEvaluation extends KnowledgeTestBase {

    /** 评测资源目录（模块目录下）。 */
    static final Path ROOT = Path.of("src/test/resources/retrieval-eval");

    /** 录制的 Embedding：文本 → 向量（float32 小端的 Base64）。 */
    static final Path EMBEDDINGS = ROOT.resolve("recording/embeddings.json");

    /** 录制的 rerank：查询 → 候选文本 → 得分。 */
    static final Path RERANKS = ROOT.resolve("recording/reranks.json");

    /** 每个问题检索的切片数。 */
    static final int TOP_K = 10;

    /** 评测知识库的 rerank 模型 ID（替身不校验，录制的是 DashScope 的结果）。 */
    static final String RERANK_MODEL = "mdl_eval_rerank";

    /** 四种检索方式，按评测记录中的顺序。 */
    static final List<SearchPlan> PLANS =
            List.of(SearchPlan.VECTOR_ONLY, SearchPlan.FULL_TEXT_ONLY, SearchPlan.HYBRID, SearchPlan.STANDARD);

    @Autowired
    KnowledgeSearchService search;

    /**
     * 评测集中的一个问题。
     *
     * @param id 编号
     * @param question 问题
     * @param file 标准答案所在的文件
     * @param clause 标准答案所在的条款标题
     */
    record Question(String id, String question, String file, String clause) {}

    /**
     * 一种检索方式的结果。
     *
     * @param plan 检索方式
     * @param ranks 每个问题标准答案的排名（从 1 起，没有命中为 0），按问题顺序
     */
    record Metrics(SearchPlan plan, List<Integer> ranks) {

        double hitAt5() {
            return ranks.stream().filter(rank -> rank >= 1 && rank <= 5).count() / (double) ranks.size();
        }

        double mrr() {
            return ranks.stream()
                            .mapToDouble(rank -> rank == 0 ? 0 : 1.0 / rank)
                            .sum()
                    / ranks.size();
        }

        String row() {
            return String.format(Locale.ROOT, "| %-13s | %.2f | %.4f | %s |", plan.label(), hitAt5(), mrr(), ranks);
        }
    }

    /** 评测用的租户、项目与知识库。 */
    record Corpus(Tenant tenant, String knowledgeBaseId, List<String> callerTags) {}

    static JsonNode evalSet() {
        try {
            return JsonMappers.lenient().readTree(Files.readString(ROOT.resolve("eval-set.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Question> questions() {
        List<Question> questions = new ArrayList<>();
        for (JsonNode node : evalSet().get("questions")) {
            questions.add(new Question(
                    node.get("id").asString(),
                    node.get("question").asString(),
                    node.get("file").asString(),
                    node.get("clause").asString()));
        }
        return questions;
    }

    /**
     * 按评测集的顺序逐份写入 6 份制度（顺序写入让切片 ID 的次序固定，全文召回同分时的次序因此确定）。
     *
     * @return 语料
     */
    Corpus ingestCorpus() {
        JsonNode set = evalSet();
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant, RERANK_MODEL);
        for (JsonNode document : set.get("documents")) {
            String file = document.get("file").asString();
            String tags = document.get("acl_tags").asString();
            ingest(tenant, base, file, read(ROOT.resolve("corpus").resolve(file)), tags.isEmpty() ? null : tags);
        }
        List<String> callerTags = new ArrayList<>();
        set.get("caller_tags").forEach(tag -> callerTags.add(tag.asString()));
        assertThat(db().queryForObject("SELECT count(*) FROM kb.chunks WHERE knowledge_base_id = ?", Long.class, base))
                .as("每条条款一个切片")
                .isEqualTo(30);
        return new Corpus(tenant, base, callerTags);
    }

    /**
     * 用一种检索方式回答全部问题。
     *
     * @param corpus 语料
     * @param plan 检索方式
     * @return 指标
     */
    Metrics evaluate(Corpus corpus, SearchPlan plan) {
        PlatformContext context = PlatformContext.builder()
                .tenantId(corpus.tenant().tenantId())
                .projectId(corpus.tenant().projectId())
                .build();
        List<Integer> ranks = new ArrayList<>();
        for (Question question : questions()) {
            SearchOutcome outcome = PlatformContexts.call(
                    context,
                    () -> search.search(
                            new SearchRequest(
                                    List.of(corpus.knowledgeBaseId()),
                                    question.question(),
                                    TOP_K,
                                    corpus.callerTags(),
                                    null,
                                    new SearchRequest.Attribution(SearchRequest.Attribution.REST, null, null, null)),
                            plan));
            ranks.add(rank(outcome, question));
        }
        return new Metrics(plan, ranks);
    }

    static int rank(SearchOutcome outcome, Question question) {
        for (int i = 0; i < outcome.hits().size(); i++) {
            Candidate candidate = outcome.hits().get(i).candidate();
            if (candidate.filename().equals(question.file())
                    && candidate.headingPath().size() > 1
                    && candidate.headingPath().get(1).equals(question.clause())) {
                return i + 1;
            }
        }
        return 0;
    }

    static void print(List<Metrics> metrics) {
        StringBuilder table =
                new StringBuilder("\n检索评测（20 个问题，前 " + TOP_K + " 个切片）\n").append("| 方式 | 命中率@5 | MRR | 每题排名 |\n");
        metrics.forEach(m -> table.append(m.row()).append('\n'));
        System.out.println(table);
    }

    // ---- 录制文件 ----

    static Map<String, float[]> readEmbeddings() {
        JsonNode json = readJson(EMBEDDINGS);
        Map<String, float[]> vectors = new LinkedHashMap<>();
        json.get("vectors")
                .properties()
                .forEach(entry ->
                        vectors.put(entry.getKey(), decode(entry.getValue().asString())));
        return vectors;
    }

    static void writeEmbeddings(String model, Map<String, float[]> vectors) {
        ObjectNode json = JsonMappers.lenient().createObjectNode();
        json.put(
                "_说明",
                "检索评测录制的 Embedding（T19-A03）：文本 → 1024 维向量（float32 小端的 Base64）。由 RetrievalEvalEmbeddingsLiveIT 生成，"
                        + "离线回归 RetrievalEvalIT 回放；改语料或切分后要重新录制。");
        json.put("model", model);
        ObjectNode entries = json.putObject("vectors");
        new TreeMap<>(vectors).forEach((text, vector) -> entries.put(text, encode(vector)));
        writeJson(EMBEDDINGS, json);
    }

    static Map<String, Map<String, Double>> readReranks() {
        JsonNode json = readJson(RERANKS);
        Map<String, Map<String, Double>> scores = new LinkedHashMap<>();
        json.get("scores").properties().forEach(query -> {
            Map<String, Double> byDocument = new LinkedHashMap<>();
            query.getValue()
                    .properties()
                    .forEach(doc -> byDocument.put(doc.getKey(), doc.getValue().asDouble()));
            scores.put(query.getKey(), byDocument);
        });
        return scores;
    }

    static void writeReranks(String model, Map<String, Map<String, Double>> scores) {
        ObjectNode json = JsonMappers.lenient().createObjectNode();
        json.put(
                "_说明",
                "检索评测录制的 rerank 得分（T19-A03）：查询 → 候选文本（标题路径加正文）→ 得分。由 RetrievalEvalRerankLiveIT 生成，"
                        + "离线回归 RetrievalEvalIT 回放。");
        json.put("model", model);
        ObjectNode entries = json.putObject("scores");
        new TreeMap<>(scores).forEach((query, byDocument) -> {
            ObjectNode node = entries.putObject(query);
            new TreeMap<>(byDocument).forEach(node::put);
        });
        writeJson(RERANKS, json);
    }

    /**
     * 回放录制的 Embedding：没有录制的文本让测试失败（语料、切分或问题变了，要重新录制）。
     *
     * @param vectors 录制的向量
     */
    static void replayEmbeddings(Map<String, float[]> vectors) {
        PLATFORM.embedder(texts -> new StubPlatform.Embedded(
                texts.stream()
                        .map(text -> {
                            float[] vector = vectors.get(text);
                            if (vector == null) {
                                throw new IllegalStateException("录制中没有这段文本的向量，需要重新录制：" + text);
                            }
                            return vector;
                        })
                        .toList(),
                0));
    }

    private static JsonNode readJson(Path path) {
        try {
            return JsonMappers.lenient().readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("读取录制失败：" + path, e);
        }
    }

    private static void writeJson(Path path, JsonNode json) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path, JsonMappers.lenient().writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decode(String text) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
