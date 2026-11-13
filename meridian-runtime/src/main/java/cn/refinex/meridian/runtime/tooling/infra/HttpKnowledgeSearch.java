package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchHit;
import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchRequest;
import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchResult;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.port.KnowledgeSearchPort;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Mono;

/**
 * {@link KnowledgeSearchPort} 的实现（架构设计「端口」「检索流程」，T19）：经 knowledge 的内部接口
 * {@code POST /internal/v1/knowledge-bases:search} 检索，带 runtime 的服务 JWT（ADR-0044）。
 *
 * <p>租户、项目、会话、轮次与 API Key 取自当前 {@code PlatformContext}；终端用户的访问标签取自会话的
 * {@code end_user_attributes.acl_tags}，每次检索从库中读，调用方（工具、中间件、模型）给不出别的标签。knowledge 拒绝或不可用时以
 * {@link IllegalStateException} 结束，消息是给模型看的英文，不含内部细节。
 */
public final class HttpKnowledgeSearch implements KnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(HttpKnowledgeSearch.class);

    static final String PATH = "/internal/v1/knowledge-bases:search";

    private final RestClient knowledge;
    private final SessionRepository sessions;

    /**
     * 创建实现。
     *
     * @param knowledge 指向 knowledge、带服务 JWT 的客户端（{@code ServiceRestClients.create(url, "knowledge")}）
     * @param sessions 会话仓储（项目级）
     */
    public HttpKnowledgeSearch(RestClient knowledge, SessionRepository sessions) {
        this.knowledge = knowledge;
        this.sessions = sessions;
    }

    @Override
    public Mono<List<Hit>> search(Query query) {
        return PlatformContexts.currentReactive().flatMap(platform -> Blocking.call(() -> search(platform, query)));
    }

    private List<Hit> search(PlatformContext platform, Query query) {
        List<String> tags = platform.sessionId() == null
                ? List.of()
                : sessions.find(platform.sessionId())
                        .map(Session::endUserAttributes)
                        .map(attributes -> attributes.aclTags())
                        .orElseThrow(() -> new IllegalStateException("the session no longer exists"));
        InternalKnowledgeSearchRequest request = new InternalKnowledgeSearchRequest(
                        platform.tenantId(),
                        platform.projectId(),
                        new LinkedHashSet<>(query.knowledgeBaseIds()),
                        query.query())
                .topK(query.topK())
                .aclTags(new ArrayList<>(tags))
                .rewriteModel(query.rewriteModel())
                .apiKeyId(platform.apiKeyId())
                .sessionId(platform.sessionId())
                .turnId(platform.turnId());
        InternalKnowledgeSearchResult result;
        try {
            result = knowledge.post().uri(PATH).body(request).retrieve().body(InternalKnowledgeSearchResult.class);
        } catch (RestClientResponseException e) {
            log.warn("知识检索被拒绝 status={} session={}", e.getStatusCode().value(), platform.sessionId());
            throw new IllegalStateException(rejection(e.getStatusCode().value()), e);
        } catch (RestClientException e) {
            log.warn("知识检索失败 session={} error={}", platform.sessionId(), e.toString());
            throw new IllegalStateException("the knowledge service is unavailable; try again later", e);
        }
        if (result == null || result.getData() == null) {
            throw new IllegalStateException("the knowledge service returned an empty response; try again later");
        }
        return result.getData().stream().map(HttpKnowledgeSearch::toHit).toList();
    }

    private static String rejection(int status) {
        return switch (status) {
            case 404 -> "one of the knowledge bases does not exist or is not accessible";
            case 409 -> "the knowledge base's embedding or rerank model is unavailable";
            case 400 -> "the search request was rejected as invalid";
            case 429, 503 -> "the knowledge service is busy; try again later";
            default -> "the knowledge service is unavailable; try again later";
        };
    }

    private static Hit toHit(InternalKnowledgeSearchHit hit) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("chunk_id", hit.getChunkId());
        if (hit.getPageStart() != null) {
            locator.put("page_start", hit.getPageStart());
        }
        if (hit.getPageEnd() != null) {
            locator.put("page_end", hit.getPageEnd());
        }
        locator.put("heading_path", hit.getHeadingPath() == null ? List.of() : List.copyOf(hit.getHeadingPath()));
        return new Hit(
                hit.getKnowledgeBaseId(),
                hit.getDocumentId(),
                hit.getFilename(),
                hit.getContent(),
                hit.getScore() == null ? 0 : hit.getScore(),
                locator);
    }
}
