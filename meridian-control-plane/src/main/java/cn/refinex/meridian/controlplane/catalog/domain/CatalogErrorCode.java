package cn.refinex.meridian.controlplane.catalog.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 模型目录（供应商账号、模型、价格表）的错误码（ADR-0045）。 */
public enum CatalogErrorCode implements ErrorCode {
    /** 供应商账号不存在。 */
    PROVIDER_ACCOUNT_NOT_FOUND("catalog.provider_account_not_found", 404, "供应商账号不存在"),
    /** 模型不存在。 */
    MODEL_NOT_FOUND("catalog.model_not_found", 404, "模型不存在"),
    /** 价格表不存在。 */
    PRICE_BOOK_NOT_FOUND("catalog.price_book_not_found", 404, "价格表不存在"),
    /** 租户内已有同名的供应商账号、模型或价格表。 */
    NAME_TAKEN("catalog.name_taken", 409, "名称已被使用"),
    /** 资源仍被引用，不能删除；problem+json 带 {@code references}。 */
    IN_USE("catalog.in_use", 409, "资源仍被引用");

    private final String code;
    private final int status;
    private final String title;

    CatalogErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
