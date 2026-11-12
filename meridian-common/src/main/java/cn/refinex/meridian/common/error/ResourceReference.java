package cn.refinex.meridian.common.error;

import java.util.Objects;

/**
 * 引用了某个资源的另一个资源，映射为 problem+json {@code references} 数组中的一项（ADR-0045 第 9 条）。
 *
 * @param type 引用方的资源类型，snake_case，例如 {@code provider_account}；必填
 * @param id 引用方的 ID；必填
 * @param name 引用方的名称，给管理员辨认；可为空
 */
public record ResourceReference(String type, String id, String name) {

    /**
     * 创建引用。
     *
     * @throws NullPointerException 缺少类型或 ID 时
     */
    public ResourceReference {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
