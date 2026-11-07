package cn.refinex.meridian.common.error;

/**
 * 一个字段的校验失败，映射为 problem+json {@code errors} 数组中的一项。
 *
 * @param field 字段路径，使用 JSON 字段名（snake_case），嵌套用点分隔，例如 {@code metadata.title}
 * @param message 面向调用方的说明
 */
public record FieldViolation(String field, String message) {}
