package cn.refinex.meridian.controlplane.agent.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 智能体：版本的容器，属于项目（架构设计「核心概念」）。
 *
 * @param id 智能体 ID（{@code agt_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param name 名字，项目内唯一，发布时写进规格
 * @param description 说明；没有时为空字符串
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 * @param currentVersion 当前发布的版本（版本号最大的已发布版本）；没有发布过时为空
 */
public record Agent(
        String id,
        String tenantId,
        String projectId,
        String name,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        PublishedVersion currentVersion) {

    /**
     * 创建智能体。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public Agent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
    }

    /**
     * 一个已发布的版本。
     *
     * @param id 智能体版本 ID（{@code agv_…}）
     * @param version 版本号
     * @param publishedAt 发布时间
     */
    public record PublishedVersion(String id, int version, Instant publishedAt) {}
}
