package cn.refinex.meridian.controlplane.agent.infra;

import cn.refinex.meridian.controlplane.agent.app.KnowledgeCatalogClient;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link KnowledgeCatalogClient} 的开发实现：只认配置 {@code meridian.control-plane.knowledge.dev-knowledge-base-ids} 中列出的知识库，
 * 不区分项目（ADR-0047）。T18 建立 knowledge 服务后换成经它的内部接口查询。
 *
 * <p>线程安全：不可变。
 */
// TODO(T18): 经 knowledge 服务的内部接口校验知识库存在且属于该项目，删除这个开发实现
@Component
public class ConfiguredKnowledgeCatalog implements KnowledgeCatalogClient {

    private final Set<String> knowledgeBaseIds;

    /**
     * 创建实现。
     *
     * @param properties control-plane 配置
     */
    public ConfiguredKnowledgeCatalog(ControlPlaneProperties properties) {
        this.knowledgeBaseIds = Set.copyOf(properties.knowledge().devKnowledgeBaseIds());
    }

    @Override
    public boolean exists(String tenantId, String projectId, String knowledgeBaseId) {
        return knowledgeBaseIds.contains(knowledgeBaseId);
    }
}
