package cn.refinex.meridian.controlplane.apikey.domain;

/** API Key 的状态，由吊销时间与过期时间推出，不单独存储。 */
public enum ApiKeyStatus {
    /** 有效。 */
    ACTIVE("active"),
    /** 已吊销，不可恢复。 */
    REVOKED("revoked"),
    /** 已过期。 */
    EXPIRED("expired");

    private final String value;

    ApiKeyStatus(String value) {
        this.value = value;
    }

    /**
     * 返回 API 中的取值。
     *
     * @return 例如 {@code active}
     */
    public String value() {
        return value;
    }
}
