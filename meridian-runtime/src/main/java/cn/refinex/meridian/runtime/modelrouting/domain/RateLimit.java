package cn.refinex.meridian.runtime.modelrouting.domain;

/**
 * "供应商账号加模型"的限流参数（架构设计「模型调用」第 2 步）。
 *
 * @param rpm 每分钟请求数上限；0 表示不限
 * @param tpm 每分钟 token 数上限（按估算的输入加最大输出计）；0 表示不限
 */
public record RateLimit(int rpm, int tpm) {

    /** 不限流。 */
    public static final RateLimit UNLIMITED = new RateLimit(0, 0);

    /**
     * 创建限流参数。
     *
     * @throws IllegalArgumentException 任一项为负时
     */
    public RateLimit {
        if (rpm < 0 || tpm < 0) {
            throw new IllegalArgumentException("rpm 与 tpm 不能为负");
        }
    }
}
