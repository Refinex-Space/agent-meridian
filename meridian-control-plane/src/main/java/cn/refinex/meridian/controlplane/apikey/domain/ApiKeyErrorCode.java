package cn.refinex.meridian.controlplane.apikey.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** API Key 管理与校验的错误码。 */
public enum ApiKeyErrorCode implements ErrorCode {
    /** API Key 不存在、已吊销、已过期，或所属项目、租户已删除（内部校验接口）；管理接口中表示不存在。 */
    NOT_FOUND("api_key.not_found", 404, "API Key 不存在");

    private final String code;
    private final int status;
    private final String title;

    ApiKeyErrorCode(String code, int status, String title) {
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
