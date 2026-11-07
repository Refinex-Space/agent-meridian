package cn.refinex.meridian.engine.spi.spec;

/**
 * 上下文压缩配置，见架构设计「上下文管理」。
 *
 * <p>压缩阈值等于模型上下文窗口减去 {@code reservedTokens}。摘要提示词由平台统一维护，不随智能体配置；
 * 一期没有长期记忆，压缩前不刷写记忆、不卸载原文（{@code flushBeforeCompact(false)}、
 * {@code offloadBeforeCompact(false)}，见架构设计「引擎装配」）。
 *
 * @param reservedTokens 为本轮后续输出与工具结果预留的 token 数，取值大于 0
 */
public record CompactionSpec(int reservedTokens) {

    /** 预留 token 的默认值。架构设计「上下文管理」规定 20000。 */
    public static final int DEFAULT_RESERVED_TOKENS = 20_000;

    /**
     * 创建压缩配置。
     *
     * @throws IllegalArgumentException 预留 token 数不大于 0 时
     */
    public CompactionSpec {
        if (reservedTokens <= 0) {
            throw new IllegalArgumentException("reservedTokens 必须大于 0：" + reservedTokens);
        }
    }

    /**
     * 默认配置。
     *
     * @return 预留 {@value #DEFAULT_RESERVED_TOKENS} token 的配置
     */
    public static CompactionSpec defaults() {
        return new CompactionSpec(DEFAULT_RESERVED_TOKENS);
    }
}
