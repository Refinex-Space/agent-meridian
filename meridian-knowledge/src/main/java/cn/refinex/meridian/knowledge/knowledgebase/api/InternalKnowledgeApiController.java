package cn.refinex.meridian.knowledge.knowledgebase.api;

import cn.refinex.meridian.api.internal.model.InternalKnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.openapi.internal.InternalKnowledgeApi;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口 {@code GET /internal/v1/knowledge-bases/{id}}（ADR-0051）：只接受 control-plane（发布智能体版本时校验知识库）与 runtime 的
 * 服务 JWT，不按租户过滤，调用方比较租户与项目。
 */
@RestController
public class InternalKnowledgeApiController implements InternalKnowledgeApi {

    private final KnowledgeBaseService bases;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param bases 知识库用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalKnowledgeApiController(KnowledgeBaseService bases, HttpServletRequest request) {
        this.bases = bases;
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
}
