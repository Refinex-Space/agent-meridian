package cn.refinex.meridian.controlplane.skill.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 技能：版本化的 {@code SKILL.md} 与附带文件，属于项目。
 *
 * @param id 技能 ID（{@code skl_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param name 技能名（frontmatter 的 {@code name}），项目内唯一
 * @param description 最新版本 frontmatter 的 {@code description}
 * @param latestVersion 最新版本号
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedAt 最近一次上传新版本的时间
 */
public record Skill(
        String id,
        String tenantId,
        String projectId,
        String name,
        String description,
        int latestVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建技能。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public Skill {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
