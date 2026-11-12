package cn.refinex.meridian.controlplane.catalog.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 每百万 token 的单价（架构设计「计量与预算」：输入、输出、缓存读、缓存写、推理分别定价），十进制，最多 12 位整数、6 位小数。金额用
 * {@link BigDecimal}，不用浮点数。
 *
 * @param input 输入
 * @param output 输出
 * @param cacheRead 命中提示词缓存的输入
 * @param cacheWrite 写入提示词缓存的输入
 * @param reasoning 推理
 */
public record TokenPrices(
        BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite, BigDecimal reasoning) {

    /** 整数部分的上限（不含）：{@code numeric(18, 6)}。 */
    private static final BigDecimal LIMIT = new BigDecimal("1000000000000");

    /**
     * 创建单价。
     *
     * @throws NullPointerException 缺少单价时
     * @throws IllegalArgumentException 单价为负、超过 6 位小数或超出范围时
     */
    public TokenPrices {
        Stream.of(input, output, cacheRead, cacheWrite, reasoning).forEach(price -> {
            Objects.requireNonNull(price, "price");
            if (price.signum() < 0 || price.stripTrailingZeros().scale() > 6 || price.compareTo(LIMIT) >= 0) {
                throw new IllegalArgumentException("单价必须在 0 到 10^12 之间，最多 6 位小数：" + price);
            }
        });
    }
}
