package cn.refinex.meridian.controlplane.metering.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 用量报表的错误码（ADR-0048）。 */
public enum MeteringErrorCode implements ErrorCode {
    /** 时间范围或聚合维度不合法。 */
    INVALID_QUERY("usage.invalid_query", 400, "用量查询参数不合法"),
    /** 导出的明细超过行数上限（ADR-0053）。 */
    EXPORT_TOO_LARGE("usage.export_too_large", 400, "导出的用量明细过多");

    private final String code;
    private final int status;
    private final String title;

    MeteringErrorCode(String code, int status, String title) {
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
