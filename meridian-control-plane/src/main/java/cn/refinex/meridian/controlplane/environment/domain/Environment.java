package cn.refinex.meridian.controlplane.environment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 环境与它当前修订的配置（架构设计「核心概念」）。
 *
 * @param id 环境 ID（{@code env_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param name 名字，项目内唯一
 * @param revision 当前修订号，从 1 开始
 * @param config 当前修订的配置
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 */
public record Environment(
        String id,
        String tenantId,
        String projectId,
        String name,
        long revision,
        EnvironmentConfig config,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建环境。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public Environment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(config, "config");
    }
}
