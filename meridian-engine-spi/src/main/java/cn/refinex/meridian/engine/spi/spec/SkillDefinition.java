package cn.refinex.meridian.engine.spi.spec;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一个技能版本的内容：{@code SKILL.md} 正文与附带资源，由平台技能仓库按 {@link SkillRef} 解析（架构设计「引擎装配」中的
 * {@code skillRepository}）。
 *
 * <p>内核把它放在受保护路径 {@code skills/<name>/} 下，对智能体只读；技能清单从 {@code SKILL.md} 的 frontmatter
 * （{@code name}、{@code description}）生成，{@code load_skill_through_path} 读取正文与资源。
 *
 * @param skillId 技能 ID（{@code skl_…}）；必填
 * @param version 技能版本号，从 1 开始
 * @param name 技能名，与 {@link SkillRef#name()} 一致；必填
 * @param files 技能目录下的文件，键为相对路径（如 {@code SKILL.md}、{@code templates/report.md}），值为 UTF-8 文本；
 *     必须包含 {@value #SKILL_FILE}，路径不能以 {@code /} 开头、不能含 {@code ..}
 */
public record SkillDefinition(String skillId, int version, String name, Map<String, String> files) {

    /** 技能正文的文件名。 */
    public static final String SKILL_FILE = "SKILL.md";

    /**
     * 创建技能内容。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 版本号小于 1、缺少 {@value #SKILL_FILE} 或路径不合法时
     */
    public SkillDefinition {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(files, "files");
        if (version < 1) {
            throw new IllegalArgumentException("技能版本号必须从 1 开始：" + version);
        }
        if (!files.containsKey(SKILL_FILE)) {
            throw new IllegalArgumentException("技能 " + name + " 缺少 " + SKILL_FILE);
        }
        for (String path : files.keySet()) {
            if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                throw new IllegalArgumentException("技能 " + name + " 的文件路径不合法：" + path);
            }
        }
        files = Collections.unmodifiableMap(new TreeMap<>(files));
    }
}
