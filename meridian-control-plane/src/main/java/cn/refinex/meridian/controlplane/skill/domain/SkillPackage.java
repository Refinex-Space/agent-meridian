package cn.refinex.meridian.controlplane.skill.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 校验过的技能包（ADR-0047）：{@code SKILL.md} 必须有 YAML frontmatter，{@code name}（小写字母、数字、短横线，至多 64 个字符）与
 * {@code description}（至多 1024 个字符）必填；文件是 UTF-8 文本，路径为相对路径、不含 {@code ..} 与反斜杠；至多 100 个文件，单个至多
 * 1 MB、合计至多 5 MB。与 engine-spi 的 {@code SkillDefinition} 的约束一致，runtime 收到的内容一定能构造出来。
 *
 * @param name frontmatter 的 {@code name}
 * @param description frontmatter 的 {@code description}
 * @param files 文件：相对路径 → 内容，按路径排序
 */
public record SkillPackage(String name, String description, SortedMap<String, String> files) {

    /** 技能正文的文件名。 */
    public static final String SKILL_FILE = "SKILL.md";

    /** 文件数上限。 */
    public static final int MAX_FILES = 100;

    /** 单个文件的上限，单位字节。 */
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    /** 合计上限，单位字节。 */
    public static final int MAX_TOTAL_BYTES = 5 * 1024 * 1024;

    /** frontmatter 中 {@code description} 的长度上限。 */
    public static final int MAX_DESCRIPTION = 1024;

    /** 技能名：与 Agent Skills 的约定一致。 */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /**
     * 创建技能包。
     *
     * @throws NullPointerException 缺少字段时
     */
    public SkillPackage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        files = new TreeMap<>(files);
    }

    /**
     * 校验并解析上传的文件。
     *
     * @param uploaded 上传的文件，按请求中的顺序：相对路径与内容
     * @return 技能包
     * @throws ValidationException 不合法时，字段错误指向 {@code files.N.path}、{@code files.N.content} 或 {@code files}
     */
    public static SkillPackage parse(List<Map.Entry<String, String>> uploaded) {
        List<FieldViolation> violations = new ArrayList<>();
        SortedMap<String, String> files = new TreeMap<>();
        long total = 0;
        if (uploaded.size() > MAX_FILES) {
            violations.add(new FieldViolation("files", "至多 " + MAX_FILES + " 个文件"));
        }
        for (int i = 0; i < uploaded.size(); i++) {
            String path = uploaded.get(i).getKey();
            String content =
                    uploaded.get(i).getValue() == null ? "" : uploaded.get(i).getValue();
            if (path == null
                    || path.isBlank()
                    || path.startsWith("/")
                    || path.contains("\\")
                    || path.contains("..")
                    || path.endsWith("/")
                    || path.contains("//")) {
                violations.add(new FieldViolation("files." + i + ".path", "不是合法的相对路径：" + path));
                continue;
            }
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_FILE_BYTES) {
                violations.add(new FieldViolation("files." + i + ".content", "单个文件至多 1 MB"));
            }
            total += bytes;
            if (files.put(path, content) != null) {
                violations.add(new FieldViolation("files." + i + ".path", "路径重复：" + path));
            }
        }
        if (total > MAX_TOTAL_BYTES) {
            violations.add(new FieldViolation("files", "文件合计至多 5 MB"));
        }
        String skillFile = files.get(SKILL_FILE);
        String name = "";
        String description = "";
        if (skillFile == null) {
            violations.add(new FieldViolation("files", "技能包必须包含 " + SKILL_FILE));
        } else {
            Map<String, Object> frontmatter = frontmatter(skillFile, violations);
            name = frontmatter.get("name") instanceof String value ? value : "";
            description = frontmatter.get("description") instanceof String value ? value.strip() : "";
            if (!NAME.matcher(name).matches()) {
                violations.add(
                        new FieldViolation("files", SKILL_FILE + " 的 frontmatter 缺少合法的 name（小写字母、数字、短横线，至多 64 个字符）"));
            }
            if (description.isEmpty() || description.length() > MAX_DESCRIPTION) {
                violations.add(new FieldViolation(
                        "files", SKILL_FILE + " 的 frontmatter 缺少 description，或超过 " + MAX_DESCRIPTION + " 个字符"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "技能包不合法", violations);
        }
        return new SkillPackage(name, description, files);
    }

    /** 解析 Markdown 开头 {@code ---} 包围的 YAML frontmatter（只解析基本类型）；没有或不是映射时记一条错误、返回空映射。 */
    private static Map<String, Object> frontmatter(String markdown, List<FieldViolation> violations) {
        String text = markdown.startsWith("﻿") ? markdown.substring(1) : markdown;
        int end = text.indexOf("\n---", 3);
        if (!text.startsWith("---") || end < 0) {
            violations.add(new FieldViolation("files", SKILL_FILE + " 必须以 --- 包围的 YAML frontmatter 开头"));
            return Map.of();
        }
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(3, end));
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        } catch (YAMLException e) {
            violations.add(new FieldViolation("files", SKILL_FILE + " 的 frontmatter 不是合法的 YAML"));
            return Map.of();
        }
        violations.add(new FieldViolation("files", SKILL_FILE + " 的 frontmatter 必须是映射"));
        return Map.of();
    }
}
