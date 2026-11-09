package cn.refinex.meridian.controlplane.tenant.domain;

/**
 * 租户在当前事务中被删除：项目随之删除（再引起 API Key 吊销），由各上下文在同一事务中处理。
 *
 * @param tenantId 租户 ID
 * @param actor 操作者的主体标识
 */
public record TenantDeleted(String tenantId, String actor) {}
