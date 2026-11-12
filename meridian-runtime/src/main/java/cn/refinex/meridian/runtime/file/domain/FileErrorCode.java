package cn.refinex.meridian.runtime.file.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 文件的错误码（ADR-0050），码值只增不改。 */
public enum FileErrorCode implements ErrorCode {
    /** 文件不存在，或不属于当前项目。 */
    NOT_FOUND("file.not_found", 404, "文件不存在"),
    /** 内容超过大小上限（上传文件与工作区写入共用）。 */
    TOO_LARGE("file.too_large", 413, "内容超过大小上限"),
    /** 轮次开始时文件暂时无法放进工作区（对象存储不可用），本轮置回队列。 */
    STAGING_UNAVAILABLE("file.staging_unavailable", 503, "文件暂时无法放进工作区");

    private final String code;
    private final int status;
    private final String title;

    FileErrorCode(String code, int status, String title) {
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
