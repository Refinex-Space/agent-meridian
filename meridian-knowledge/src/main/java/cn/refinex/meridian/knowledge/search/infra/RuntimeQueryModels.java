package cn.refinex.meridian.knowledge.search.infra;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeErrorCode;
import cn.refinex.meridian.knowledge.search.app.QueryModels;
import cn.refinex.meridian.modelclient.ChatClient;
import cn.refinex.meridian.modelclient.ChatRequest;
import cn.refinex.meridian.modelclient.EmbeddingClient;
import cn.refinex.meridian.modelclient.EmbeddingRequest;
import cn.refinex.meridian.modelclient.ModelClientException;
import cn.refinex.meridian.modelclient.RerankClient;
import cn.refinex.meridian.modelclient.RerankRequest;
import cn.refinex.meridian.modelclient.RerankResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link QueryModels} 的实现：{@code meridian-model-client} 调 runtime 的 {@code /internal/v1/embeddings}、{@code /internal/v1/rerank}、
 * {@code /internal/v1/chat/completions}，带 knowledge 的服务 JWT（ADR-0044）。
 */
public class RuntimeQueryModels implements QueryModels {

    /**
     * 查询改写的提示词（架构设计「检索流程」第 1 步）。英文写成，输出保持问题的语言；只要一条查询，不要解释。
     */
    static final String REWRITE_PROMPT = """
            You rewrite a user's question into one concise search query for a document retrieval system.
            Keep the language of the question. Keep the key entities, numbers, terms and constraints; drop greetings, \
            politeness and filler; resolve vague wording into the terms a policy or manual would use.
            Output only the query on a single line, without quotes or explanation.""";

    /** 改写的输出上限：一条查询用不了更多。 */
    static final int REWRITE_MAX_TOKENS = 128;

    private final EmbeddingClient embeddings;
    private final RerankClient reranker;
    private final ChatClient chat;

    /**
     * 创建实现。
     *
     * @param embeddings Embedding 客户端；为空表示没有配置 runtime 地址
     * @param reranker rerank 客户端；为空同上
     * @param chat 对话客户端；为空同上
     */
    public RuntimeQueryModels(EmbeddingClient embeddings, RerankClient reranker, ChatClient chat) {
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.chat = chat;
    }

    @Override
    public float[] embed(String tenantId, String projectId, String model, String text, int dimensions) {
        return call(() -> requireConfigured(embeddings)
                .embed(new EmbeddingRequest(tenantId, projectId, model, List.of(text), dimensions))
                .vectors()
                .getFirst());
    }

    @Override
    public List<Double> rerank(String tenantId, String projectId, String model, String query, List<String> documents) {
        RerankResult result = call(() -> requireConfigured(reranker)
                .rerank(new RerankRequest(tenantId, projectId, model, query, documents, null)));
        List<Double> scores = new ArrayList<>(Collections.nCopies(documents.size(), Double.NEGATIVE_INFINITY));
        for (RerankResult.Score score : result.scores()) {
            scores.set(score.index(), score.relevance());
        }
        return scores;
    }

    @Override
    public String rewrite(String tenantId, String projectId, String model, String query) {
        return call(() -> requireConfigured(chat)
                .complete(new ChatRequest(
                        tenantId,
                        projectId,
                        model,
                        List.of(
                                new ChatRequest.Message(ChatRequest.Role.SYSTEM, REWRITE_PROMPT),
                                new ChatRequest.Message(ChatRequest.Role.USER, query)),
                        REWRITE_MAX_TOKENS,
                        0.0))
                .content());
    }

    private static <T> T requireConfigured(T client) {
        if (client == null) {
            // detail 原样返回调用方，不写配置项名（R2 审查 A-07）；配置缺失在启动日志与健康检查里看
            throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "检索需要的模型服务没有配置");
        }
        return client;
    }

    private static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ModelClientException e) {
            // 只带下游的错误码，不带下游的消息原文（可能含内部地址，R2 审查 A-07）；原因链留在服务端日志
            if (e.retryable()) {
                throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "检索的模型调用暂时失败（" + e.code() + "）", e);
            }
            throw new MeridianException(KnowledgeErrorCode.MODEL_UNAVAILABLE, "检索的模型不可用（" + e.code() + "）", e);
        }
    }
}
