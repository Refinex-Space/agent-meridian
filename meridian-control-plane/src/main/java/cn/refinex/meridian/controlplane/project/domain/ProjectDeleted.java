package cn.refinex.meridian.controlplane.project.domain;

/**
 * 项目在当前事务中被删除：其下的 API Key 吊销、项目级角色绑定删除，由各上下文在同一事务中处理。
 *
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param actor 操作者的主体标识
 */
public record ProjectDeleted(String tenantId, String projectId, String actor) {}
