package cn.refinex.meridian.engine.spi.event;

/**
 * 一次模型调用的 token 用量，对应 AgentScope {@code ChatUsage}，用于事件、预算结算与用量记录
 * （架构设计「模型调用」「计量与预算」）。
 *
 * <p>后三项都是前两项的子集，不是额外的量；供应商不报告时为 0。
 *
 * @param inputTokens 输入 token 数，不小于 0
 * @param outputTokens 输出 token 数，不小于 0
 * @param cachedTokens 命中提示词缓存的输入 token 数，是 {@code inputTokens} 的子集
 * @param cacheCreationTokens 写入提示词缓存的输入 token 数，是 {@code inputTokens} 的子集
 * @param reasoningTokens 用于推理的输出 token 数，是 {@code outputTokens} 的子集
 */
public record TokenUsage(
        int inputTokens, int outputTokens, int cachedTokens, int cacheCreationTokens, int reasoningTokens) {

    /**
     * 创建用量。
     *
     * @throws IllegalArgumentException 任一项为负时
     */
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedTokens < 0 || cacheCreationTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token 数不能为负");
        }
    }

    /**
     * 只有输入与输出的用量。
     *
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @return 用量
     */
    public static TokenUsage of(int inputTokens, int outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, 0, 0, 0);
    }

    /**
     * 两份用量逐项相加。
     *
     * @param other 另一份用量
     * @return 和
     * @throws ArithmeticException 任一项超出 {@code int} 范围时
     */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(cachedTokens, other.cachedTokens),
                Math.addExact(cacheCreationTokens, other.cacheCreationTokens),
                Math.addExact(reasoningTokens, other.reasoningTokens));
    }

    /**
     * 总 token 数。
     *
     * @return 输入与输出之和
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
