package cn.refinex.meridian.controlplane.catalog.domain;

/**
 * 每分钟的请求与 token 上限（架构设计「模型调用」第 2 步）。
 *
 * @param rpm 每分钟请求上限；为空表示不限（模型上表示用账号的）
 * @param tpm 每分钟 token 上限；为空表示不限（模型上表示用账号的）
 */
public record RateLimit(Integer rpm, Integer tpm) {

    /** 不限。 */
    public static final RateLimit NONE = new RateLimit(null, null);

    /**
     * 创建限流参数。
     *
     * @throws IllegalArgumentException 取值小于 1 时
     */
    public RateLimit {
        if ((rpm != null && rpm < 1) || (tpm != null && tpm < 1)) {
            throw new IllegalArgumentException("rpm 与 tpm 必须大于 0");
        }
    }
}
