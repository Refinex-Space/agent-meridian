package cn.refinex.meridian.controlplane.agent.infra;

import cn.refinex.meridian.api.internal.model.InternalKnowledgeBase;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.agent.app.KnowledgeCatalogClient;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link KnowledgeCatalogClient} 的实现：调用 knowledge 的内部接口 {@code GET /internal/v1/knowledge-bases/{id}}（ADR-0051），知识库存在且
 * 租户、项目都与智能体相同才算存在。出站服务 JWT 由本进程签发（{@code sub} 为 control-plane，{@code aud} 为 knowledge）。
 *
 * <p>没有配置 knowledge 的地址、knowledge 不可用或返回意外的错误时抛 503：发布被拒绝，不把"查不到"当成"不存在"。线程安全：不可变。
 */
@Component
public class KnowledgeServiceCatalog implements KnowledgeCatalogClient {

    static final String PATH = "/internal/v1/knowledge-bases/{id}";

    private final RestClient knowledge;

    /**
     * 创建实现。
     *
     * @param properties control-plane 配置，取 knowledge 的地址
     * @param clients 服务间调用客户端的构造入口
     */
    public KnowledgeServiceCatalog(ControlPlaneProperties properties, ServiceRestClients clients) {
        String url = properties.knowledge().url();
        this.knowledge = url == null || url.isBlank() ? null : clients.create(url, ServiceJwts.KNOWLEDGE);
    }

    @Override
    public boolean exists(String tenantId, String projectId, String knowledgeBaseId) {
        if (knowledge == null) {
            throw new MeridianException(
                    CommonErrorCode.SERVICE_UNAVAILABLE,
                    "没有配置 knowledge 服务（meridian.control-plane.knowledge.url），无法校验知识库");
        }
        InternalKnowledgeBase base;
        try {
            base = knowledge.get().uri(PATH, knowledgeBaseId).retrieve().body(InternalKnowledgeBase.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return false;
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        return base != null && tenantId.equals(base.getTenantId()) && projectId.equals(base.getProjectId());
    }

    private static MeridianException unavailable(Exception cause) {
        return new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "knowledge 服务暂时不可用，无法校验知识库", cause);
    }
}
