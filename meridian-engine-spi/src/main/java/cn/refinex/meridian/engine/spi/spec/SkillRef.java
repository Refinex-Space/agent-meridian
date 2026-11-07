package cn.refinex.meridian.engine.spi.spec;

import java.util.Objects;

/**
 * 智能体版本引用的一个技能版本，见架构设计「引擎装配」中的 {@code skillRepository}。
 *
 * <p>技能正文与附带资源由平台技能仓库按此引用解析，智能体不能自建技能。
 *
 * @param skillId 技能 ID（{@code skl_…}）；必填
 * @param version 技能版本号，从 1 开始；智能体版本发布时锁定
 * @param name 技能名，即 {@code load_skill_through_path} 使用的名字；必填
 */
public record SkillRef(String skillId, int version, String name) {

    /**
     * 创建技能引用。
     *
     * @throws NullPointerException 缺少技能 ID 或技能名时
     * @throws IllegalArgumentException 版本号小于 1 时
     */
    public SkillRef {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
        if (version < 1) {
            throw new IllegalArgumentException("技能版本号必须从 1 开始：" + version);
        }
    }
}
