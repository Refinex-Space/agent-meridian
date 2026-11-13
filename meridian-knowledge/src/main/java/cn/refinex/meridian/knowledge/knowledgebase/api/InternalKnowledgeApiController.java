package cn.refinex.meridian.knowledge.knowledgebase.api;

import cn.refinex.meridian.api.internal.model.InternalKnowledgeBase;
import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchRequest;
import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchResult;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.openapi.internal.InternalKnowledgeApi;
import cn.refinex.meridian.knowledge.search.api.SearchDtos;
import cn.refinex.meridian.knowledge.search.app.KnowledgeSearchService;
import cn.refinex.meridian.knowledge.search.app.SearchRequest;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口 {@code GET /internal/v1/knowledge-bases/{id}}（ADR-0051）：只接受 control-plane（发布智能体版本时校验知识库）与 runtime 的
 * 服务 JWT，不按租户过滤，调用方比较租户与项目。{@code POST /internal/v1/knowledge-bases:search}（T19）只接受 runtime：按请求体的租户与
 * 项目建立 {@code PlatformContext}，知识库必须属于它们；访问标签是会话的 {@code end_user_attributes.acl_tags}。
 */
@RestController
public class InternalKnowledgeApiController implements InternalKnowledgeApi {

    private final KnowledgeBaseService bases;
    private final KnowledgeSearchService search;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param bases 知识库用例
     * @param search 检索用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalKnowledgeApiController(
            KnowledgeBaseService bases, KnowledgeSearchService search, HttpServletRequest request) {
        this.bases = bases;
        this.search = search;
        this.request = request;
    }

    @Override
    public ResponseEntity<InternalKnowledgeBase> getInternalKnowledgeBase(String knowledgeBaseId) {
        ServiceCredentialFilter.require(request, ServiceJwts.CONTROL_PLANE, ServiceJwts.RUNTIME);
        KnowledgeBase base = bases.lookup(knowledgeBaseId);
        return ResponseEntity.ok(new InternalKnowledgeBase()
                .id(base.id())
                .tenantId(base.tenantId())
                .projectId(base.projectId())
                .name(base.name())
                .embeddingModel(base.embeddingModel()));
    }

    @Override
    public ResponseEntity<InternalKnowledgeSearchResult> searchInternalKnowledge(InternalKnowledgeSearchRequest body) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        SearchDtos.requireQuery(body.getQuery());
        PlatformContext context = PlatformContext.builder()
                .tenantId(body.getTenantId())
                .projectId(body.getProjectId())
                .sessionId(body.getSessionId())
                .turnId(body.getTurnId())
                .apiKeyId(body.getApiKeyId())
                .build();
        SearchRequest search = new SearchRequest(
                List.copyOf(body.getKnowledgeBaseIds()),
                body.getQuery(),
                body.getTopK() == null ? this.search.defaultTopK() : body.getTopK(),
                body.getAclTags(),
                body.getRewriteModel(),
                new SearchRequest.Attribution(
                        SearchRequest.Attribution.AGENT, body.getApiKeyId(), body.getSessionId(), body.getTurnId()));
        return ResponseEntity.ok(
                SearchDtos.toInternal(PlatformContexts.call(context, () -> this.search.search(search))));
    }
}
