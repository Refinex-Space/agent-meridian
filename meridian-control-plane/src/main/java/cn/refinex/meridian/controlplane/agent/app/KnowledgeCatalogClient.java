package cn.refinex.meridian.controlplane.agent.app;

/**
 * 知识库目录：发布智能体版本时校验它绑定的知识库存在且属于同一项目（ADR-0047 的六项校验之一）。知识库由 knowledge 服务管理（T18）。
 */
public interface KnowledgeCatalogClient {

    /**
     * 知识库是否存在且属于该项目。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param knowledgeBaseId 知识库 ID（{@code kb_…}）
     * @return 存在时为 true
     */
    boolean exists(String tenantId, String projectId, String knowledgeBaseId);
}
