package cn.refinex.meridian.controlplane.project.domain;

import java.time.Instant;

/**
 * 项目（架构设计「租户层级」）：隔离与授权的基本单位。
 *
 * @param id 项目 ID（{@code prj_…}）
 * @param tenantId 所属租户 ID
 * @param name 名称，1 到 128 个字符
 * @param description 说明；空字符串表示没有
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 */
public record Project(
        String id, String tenantId, String name, String description, Instant createdAt, Instant updatedAt) {}
