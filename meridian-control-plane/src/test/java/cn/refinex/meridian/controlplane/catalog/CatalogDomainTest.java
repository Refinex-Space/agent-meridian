package cn.refinex.meridian.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 模型目录的值对象（T15a）：单价的范围与精度、错误码。 */
class CatalogDomainTest {

    @Test
    void pricesAcceptSixDecimalsAndZero() {
        TokenPrices prices = prices("0.000001", "0", "999999999999.999999", "1.5", "2.000000000");

        assertThat(prices.input()).isEqualByComparingTo("0.000001");
        assertThat(prices.cacheRead()).isEqualByComparingTo("999999999999.999999");
    }

    @ParameterizedTest(name = "单价 {0} 不合法")
    @ValueSource(strings = {"-0.1", "0.0000001", "1000000000000"})
    void pricesRejectNegativeTooPreciseOrTooLarge(String value) {
        assertThatThrownBy(() -> prices(value, "1", "1", "1", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(CatalogErrorCode.values());
    }

    private static TokenPrices prices(
            String input, String output, String cacheRead, String cacheWrite, String reasoning) {
        return new TokenPrices(
                new BigDecimal(input),
                new BigDecimal(output),
                new BigDecimal(cacheRead),
                new BigDecimal(cacheWrite),
                new BigDecimal(reasoning));
    }
}
