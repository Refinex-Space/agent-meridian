package cn.refinex.meridian.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities;
import cn.refinex.meridian.controlplane.catalog.domain.ModelKind;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBook;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBookVersion;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import cn.refinex.meridian.controlplane.catalog.domain.RateLimit;
import cn.refinex.meridian.controlplane.catalog.domain.ResolvedModel;
import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 模型目录的值对象（T15a）：单价的范围与精度、取值的往返、模型与账号的不变量、错误码。 */
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

    @Test
    void enumValuesRoundTrip() {
        for (ProviderType type : ProviderType.values()) {
            assertThat(ProviderType.fromValue(type.value())).contains(type);
        }
        for (ModelKind kind : ModelKind.values()) {
            assertThat(ModelKind.fromValue(kind.value())).contains(kind);
        }
        assertThat(ProviderType.fromValue("ollama")).isEmpty();
        assertThat(ModelKind.fromValue("audio")).isEmpty();
        assertThat(ProviderType.OPENAI_COMPATIBLE.value()).isEqualTo("openai_compatible");
    }

    @Test
    void rateLimitsAreNullableButPositive() {
        assertThat(RateLimit.NONE.rpm()).isNull();
        assertThat(new RateLimit(60, null).rpm()).isEqualTo(60);
        assertThatThrownBy(() -> new RateLimit(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(null, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelsCheckTheirInvariants() {
        CatalogModel model = model("mdl_1", null, 8192, 1024, null);
        ProviderAccount account = account();

        assertThat(new ResolvedModel(model, account, 3).priceBookVersion()).isEqualTo(3);
        assertThat(model.capabilities()).isEqualTo(ModelCapabilities.NONE);
        assertThatThrownBy(() -> model("mdl_1", "mdl_1", 8192, 1024, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("备用模型");
        assertThatThrownBy(() -> model("mdl_1", null, 0, 1024, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model("mdl_1", null, 8192, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model("mdl_1", null, 8192, 1024, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedModel(model, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderAccount(
                        "prv_1", "ten_1", "n", null, null, null, RateLimit.NONE, "u", Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void priceBooksAndVersionsRequireTheirFields() {
        TokenPrices prices = prices("1", "2", "0.1", "1", "2");
        PriceBook book = new PriceBook("prb_1", "ten_1", "价格表", "CNY", 2, "u", Instant.EPOCH, Instant.EPOCH);
        PriceBookVersion version = new PriceBookVersion("prb_1", 2, prices, Instant.EPOCH, "u");

        assertThat(book.currentVersion()).isEqualTo(version.version());
        assertThatThrownBy(() -> new PriceBook("prb_1", "ten_1", "价格表", null, 1, "u", Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PriceBookVersion("prb_1", 1, null, Instant.EPOCH, "u"))
                .isInstanceOf(NullPointerException.class);
    }

    private static CatalogModel model(
            String id, String fallback, int contextWindow, int maxOutput, Integer maxBatchSize) {
        return new CatalogModel(
                id,
                "ten_1",
                "prv_1",
                "模型",
                ModelKind.CHAT,
                "up",
                contextWindow,
                maxOutput,
                ModelCapabilities.NONE,
                fallback,
                null,
                null,
                maxBatchSize,
                RateLimit.NONE,
                "u",
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static ProviderAccount account() {
        return new ProviderAccount(
                "prv_1",
                "ten_1",
                "账号",
                ProviderType.ANTHROPIC,
                null,
                null,
                RateLimit.NONE,
                "u",
                Instant.EPOCH,
                Instant.EPOCH);
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
