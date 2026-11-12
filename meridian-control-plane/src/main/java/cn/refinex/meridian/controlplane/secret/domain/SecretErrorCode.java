package cn.refinex.meridian.controlplane.secret.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 凭据库的错误码（ADR-0045）。 */
public enum SecretErrorCode implements ErrorCode {
    /** 凭据不存在；内部解析时还包括"不属于请求中的租户或项目"，两者不区分。 */
    NOT_FOUND("secret.not_found", 404, "凭据不存在"),
    /** 同一范围（租户级或同一项目）内已有同名凭据。 */
    NAME_TAKEN("secret.name_taken", 409, "凭据名称已被使用"),
    /** 凭据仍被其他资源引用，不能删除；problem+json 带 {@code references}。 */
    IN_USE("secret.in_use", 409, "凭据仍被引用"),
    /** 密钥加密服务（Vault 等）暂时不可用，或数据密钥无法解包。 */
    ENCRYPTION_UNAVAILABLE("secret.encryption_unavailable", 503, "密钥加密服务暂时不可用");

    private final String code;
    private final int status;
    private final String title;

    SecretErrorCode(String code, int status, String title) {
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
