package cn.refinex.meridian.knowledge.document.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传文档时的访问标签（公开 API 的 {@code acl_tags}，逗号分隔）：至多 {@value #MAX_TAGS} 个，每个 1 至 {@value #MAX_LENGTH} 个字母、
 * 数字与 {@code ._:-@/}；去重并保留首次出现的顺序。
 */
public final class AclTags {

    /** 标签数的上限。 */
    public static final int MAX_TAGS = 32;

    /** 单个标签的长度上限。 */
    public static final int MAX_LENGTH = 128;

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9._:@/-]{1," + MAX_LENGTH + "}");

    private AclTags() {}

    /**
     * 解析。
     *
     * @param raw 逗号分隔的标签；可为空，表示没有标签
     * @return 标签
     * @throws ValidationException 标签不合法或过多时（字段 {@code acl_tags}）
     */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String tag = part.strip();
            if (!TAG.matcher(tag).matches()) {
                throw invalid("标签必须是 1 至 " + MAX_LENGTH + " 个字母、数字或 ._:-@/ 字符：" + tag);
            }
            tags.add(tag);
        }
        if (tags.size() > MAX_TAGS) {
            throw invalid("标签至多 " + MAX_TAGS + " 个");
        }
        return List.copyOf(tags);
    }

    private static ValidationException invalid(String message) {
        return new ValidationException(
                CommonErrorCode.VALIDATION_FAILED, "访问标签不合法", List.of(new FieldViolation("acl_tags", message)));
    }
}
