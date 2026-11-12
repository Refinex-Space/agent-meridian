package cn.refinex.meridian.common.error;

import java.util.List;

/**
 * 资源仍被其他资源引用，不能删除（或不能改成与引用方矛盾的状态），409；problem+json 另输出 {@code references} 列出引用方
 * （ADR-0045 第 9 条）。
 */
public class ReferencedException extends ConflictException {

    private final transient List<ResourceReference> references;

    /**
     * 创建异常。
     *
     * @param errorCode 领域错误码，HTTP 状态应为 409
     * @param detail 面向调用方的具体说明
     * @param references 引用方，至少一个
     * @throws IllegalArgumentException 没有引用方时
     */
    public ReferencedException(ErrorCode errorCode, String detail, List<ResourceReference> references) {
        super(errorCode, detail);
        if (references == null || references.isEmpty()) {
            throw new IllegalArgumentException("references 不能为空");
        }
        this.references = List.copyOf(references);
    }

    /**
     * 返回引用方。
     *
     * @return 引用方，不为空
     */
    public List<ResourceReference> references() {
        return references;
    }
}
