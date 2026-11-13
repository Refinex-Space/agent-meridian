package cn.refinex.meridian.knowledge.search.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.knowledge.config.KnowledgeProperties;
import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.infra.KnowledgeBaseRepository;
import cn.refinex.meridian.knowledge.metering.app.StorageMetering;
import cn.refinex.meridian.knowledge.search.domain.Candidate;
import cn.refinex.meridian.knowledge.search.domain.ReciprocalRankFusion;
import cn.refinex.meridian.knowledge.search.domain.ScoredCandidate;
import cn.refinex.meridian.knowledge.search.domain.SearchPlan;
import cn.refinex.meridian.knowledge.search.infra.ChunkSearchRepository;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识库检索（架构设计「检索流程」）：
 *
 * <ol>
 *   <li>知识库都要属于当前项目，否则 404（与不存在相同）；
 *   <li>可选的查询改写（小模型），失败时用原查询，不让检索失败；
 *   <li>并行召回：每个 Embedding 模型一路向量召回（不同模型的距离不可比，各自成一路），加一路全文召回，各取前 50，过滤在 SQL 中；
 *   <li>RRF 融合；
 *   <li>知识库设置了 rerank 模型时，前 {@code rerank-candidates} 个融合候选经 runtime 重排，取前 k（多个知识库时取 ID 最小的那个
 *       设置了模型的知识库的模型）；
 *   <li>记一条 {@code knowledge_search_calls} 用量（outbox）。
 * </ol>
 *
 * <p>调用方须已绑定带项目的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    /** 改写结果的长度上限，与接口的查询长度上限一致；更长的输出视为改写失败。 */
    static final int MAX_QUERY_LENGTH = 2000;

    private final KnowledgeBaseRepository bases;
    private final ChunkSearchRepository chunks;
    private final QueryModels models;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final KnowledgeProperties.Search settings;
    private final int dimensions;
    private final ReciprocalRankFusion fusion;

    /**
     * 创建用例。
     *
     * @param bases 知识库的项目级仓储
     * @param chunks 两路召回
     * @param models 经 runtime 的模型调用
     * @param outbox outbox 写入器
     * @param transactions 事务模板
     * @param clock 时钟
     * @param properties knowledge 配置，取检索参数与向量维度
     */
    public KnowledgeSearchService(
            KnowledgeBaseRepository bases,
            ChunkSearchRepository chunks,
            QueryModels models,
            OutboxWriter outbox,
            TransactionTemplate transactions,
            Clock clock,
            KnowledgeProperties properties) {
        this.bases = bases;
        this.chunks = chunks;
        this.models = models;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = properties.search();
        this.dimensions = properties.embedding().dimensions();
        this.fusion = new ReciprocalRankFusion(settings.rrfK());
        if (settings.rerankCandidates() < 1 || settings.rerankCandidates() > 100) {
            throw new IllegalStateException("meridian.knowledge.search.rerank-candidates 必须在 1 至 100 之间");
        }
    }

    /**
     * 按架构设计的完整流程检索。
     *
     * @param request 入参
     * @return 结果
     * @throws cn.refinex.meridian.common.error.NotFoundException 有知识库不存在或不属于当前项目时
     * @throws MeridianException 模型不可用（409）或暂时失败（503）时
     */
    public SearchOutcome search(SearchRequest request) {
        return search(request, SearchPlan.STANDARD);
    }

    /**
     * 按指定的方式检索（检索评测对比用）。
     *
     * @param request 入参
     * @param plan 召回与重排方式
     * @return 结果
     * @throws cn.refinex.meridian.common.error.NotFoundException 有知识库不存在或不属于当前项目时
     * @throws MeridianException 模型不可用（409）或暂时失败（503）时
     */
    public SearchOutcome search(SearchRequest request, SearchPlan plan) {
        PlatformContext context = PlatformContexts.current();
        List<KnowledgeBase> targets = new ArrayList<>(request.knowledgeBaseIds().size());
        for (String id : request.knowledgeBaseIds()) {
            targets.add(bases.find(id).orElseThrow(() -> KnowledgeBaseService.notFound(id)));
        }
        String query = request.rewriteModel() == null ? request.query() : rewrite(context, request);
        List<List<Candidate>> rankings = recall(context, targets, query, request.callerTags(), plan);
        List<ScoredCandidate> fused = fusion.fuse(rankings);
        String rerankModel = plan.rerank() ? rerankModel(targets) : null;
        List<ScoredCandidate> hits = rerankModel == null || fused.isEmpty()
                ? fused.subList(0, Math.min(request.topK(), fused.size()))
                : rerank(context, rerankModel, query, fused, request.topK());
        meter(context, request);
        return new SearchOutcome(query, hits, rerankModel != null && !fused.isEmpty());
    }

    /**
     * 请求没有给出切片数时的默认值。
     *
     * @return 默认切片数
     */
    public int defaultTopK() {
        return settings.defaultTopK();
    }

    private String rewrite(PlatformContext context, SearchRequest request) {
        try {
            String output =
                    models.rewrite(context.tenantId(), context.projectId(), request.rewriteModel(), request.query());
            String rewritten = cleanRewrite(output);
            return rewritten == null ? request.query() : rewritten;
        } catch (MeridianException e) {
            // 改写只是提升召回，失败时用原查询检索，不让检索因此失败
            log.warn(
                    "查询改写失败，使用原查询 model={} code={}",
                    request.rewriteModel(),
                    e.errorCode().code());
            return request.query();
        }
    }

    /**
     * 清理改写的输出：取第一个非空行，去掉首尾空白与成对的引号。
     *
     * @param output 模型输出
     * @return 查询；输出为空或过长时为空
     */
    static String cleanRewrite(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.strip().split("\\R")) {
            String text = line.strip();
            if (text.length() >= 2
                    && ("\"'“「`".indexOf(text.charAt(0)) >= 0)
                    && ("\"'”」`".indexOf(text.charAt(text.length() - 1)) >= 0)) {
                text = text.substring(1, text.length() - 1).strip();
            }
            if (!text.isEmpty()) {
                return text.length() > MAX_QUERY_LENGTH ? null : text;
            }
        }
        return null;
    }

    /** 并行召回：每个 Embedding 模型一路向量召回，加一路全文召回。每路在自己的虚拟线程上，带同一个平台上下文。 */
    private List<List<Candidate>> recall(
            PlatformContext context,
            List<KnowledgeBase> targets,
            String query,
            List<String> callerTags,
            SearchPlan plan) {
        Map<String, List<String>> byModel = new LinkedHashMap<>();
        for (KnowledgeBase base : targets) {
            byModel.computeIfAbsent(base.embeddingModel(), model -> new ArrayList<>())
                    .add(base.id());
        }
        List<String> allIds = targets.stream().map(KnowledgeBase::id).toList();
        List<Callable<List<Candidate>>> tasks = new ArrayList<>();
        if (plan.vector()) {
            byModel.forEach((model, ids) -> tasks.add(() -> {
                float[] vector = models.embed(context.tenantId(), context.projectId(), model, query, dimensions);
                return chunks.nearest(ids, vector, callerTags, settings.vectorCandidates());
            }));
        }
        if (plan.fullText()) {
            tasks.add(() -> chunks.matching(allIds, query, callerTags, settings.fullTextCandidates()));
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Candidate>>> futures = new ArrayList<>(tasks.size());
            for (Callable<List<Candidate>> task : tasks) {
                futures.add(executor.submit(() -> PlatformContexts.call(context, task::call)));
            }
            List<List<Candidate>> rankings = new ArrayList<>(futures.size());
            for (Future<List<Candidate>> future : futures) {
                rankings.add(await(future));
            }
            return rankings;
        }
    }

    private static List<Candidate> await(Future<List<Candidate>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检索被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("召回失败", e.getCause());
        }
    }

    /**
     * 按知识库 ID 排序后第一个设置了 rerank 模型的知识库的模型：一次检索只用一个模型，得分才可比；按 ID 选，与请求中知识库的顺序无关
     * （内部接口的知识库是集合）。
     */
    private static String rerankModel(List<KnowledgeBase> targets) {
        return targets.stream()
                .sorted(Comparator.comparing(KnowledgeBase::id))
                .map(KnowledgeBase::rerankModel)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<ScoredCandidate> rerank(
            PlatformContext context, String model, String query, List<ScoredCandidate> fused, int topK) {
        List<ScoredCandidate> pool = fused.subList(0, Math.min(settings.rerankCandidates(), fused.size()));
        List<Double> scores = models.rerank(
                context.tenantId(),
                context.projectId(),
                model,
                query,
                pool.stream().map(item -> item.candidate().rerankText()).toList());
        List<ScoredCandidate> reranked = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            reranked.add(new ScoredCandidate(pool.get(i).candidate(), scores.get(i)));
        }
        // 稳定排序：得分相同的保持融合的顺序
        reranked.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
        return reranked.subList(0, Math.min(topK, reranked.size()));
    }

    private void meter(PlatformContext context, SearchRequest request) {
        SearchRequest.Attribution attribution = request.attribution();
        SearchUsageMessage message = new SearchUsageMessage(
                SearchUsageMessage.KIND,
                clock.instant(),
                context.tenantId(),
                context.projectId(),
                attribution.apiKeyId(),
                attribution.sessionId(),
                attribution.turnId(),
                attribution.source(),
                request.knowledgeBaseIds(),
                1);
        transactions.executeWithoutResult(status -> outbox.append(StorageMetering.TOPIC, message));
    }
}
