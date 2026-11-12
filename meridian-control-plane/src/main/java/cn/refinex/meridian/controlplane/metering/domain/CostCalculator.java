package cn.refinex.meridian.controlplane.metering.domain;

import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 按价格表版本的单价计算成本（架构设计「计量与预算」，ADR-0048 第 3 条）。单价是每百万 token，最多 6 位小数，所以成本最多 12 位小数，
 * 用 {@link BigDecimal} 精确计算、不舍入。
 *
 * <p>缓存读写是输入的子集、推理是输出的子集（runtime 用量消息的约定）：未命中缓存的输入、缓存读、缓存写分别按输入、缓存读、缓存写单价，
 * 非推理输出与推理分别按输出、推理单价。子集超过总量时（供应商报告不一致）按 0 计未命中部分，不出现负数。
 */
public final class CostCalculator {

    /** 成本的小数位数：单价 6 位小数除以一百万。 */
    public static final int SCALE = 12;

    private CostCalculator() {}

    /**
     * 计算一条模型 token 记录的成本。
     *
     * @param record 记录，种类为模型 token
     * @param prices 记录中价格表版本的单价
     * @return 成本，{@value #SCALE} 位小数
     */
    public static BigDecimal cost(UsageRecord record, TokenPrices prices) {
        long uncachedInput = Math.max(0, record.inputTokens() - record.cacheReadTokens() - record.cacheWriteTokens());
        long plainOutput = Math.max(0, record.outputTokens() - record.reasoningTokens());
        BigDecimal perMillion = prices.input()
                .multiply(BigDecimal.valueOf(uncachedInput))
                .add(prices.cacheRead().multiply(BigDecimal.valueOf(record.cacheReadTokens())))
                .add(prices.cacheWrite().multiply(BigDecimal.valueOf(record.cacheWriteTokens())))
                .add(prices.output().multiply(BigDecimal.valueOf(plainOutput)))
                .add(prices.reasoning().multiply(BigDecimal.valueOf(record.reasoningTokens())));
        return perMillion.movePointLeft(6).setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
