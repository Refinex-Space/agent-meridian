package cn.refinex.meridian.controlplane.skill.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 技能的一个版本，只追加、不可修改；文件内容在对象存储的 {@code objectRoot} 之下。
 *
 * @param skillId 技能 ID
 * @param version 版本号，从 1 开始
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param name frontmatter 的 {@code name}
 * @param description frontmatter 的 {@code description}
 * @param files 文件清单，按路径排序
 * @param objectRoot 对象键前缀，文件键为它加相对路径
 * @param createdBy 上传者
 * @param createdAt 上传时间
 */
public record SkillVersion(
        String skillId,
        int version,
        String tenantId,
        String projectId,
        String name,
        String description,
        List<SkillFile> files,
        String objectRoot,
        String createdBy,
        Instant createdAt) {

    /**
     * 创建版本。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public SkillVersion {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
        files = List.copyOf(files);
    }

    /**
     * 对象存储中该版本的键前缀。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     * @param version 版本号
     * @return {@code cp/skills/{tenant}/{project}/{skill}/v{version}/}
     */
    public static String objectRoot(String tenantId, String projectId, String skillId, int version) {
        return "cp/skills/" + tenantId + "/" + projectId + "/" + skillId + "/v" + version + "/";
    }
}
