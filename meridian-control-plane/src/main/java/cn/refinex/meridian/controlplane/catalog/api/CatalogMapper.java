package cn.refinex.meridian.controlplane.catalog.api;

import cn.refinex.meridian.api.admin.model.ModelCapabilities;
import cn.refinex.meridian.api.admin.model.ModelKind;
import cn.refinex.meridian.api.admin.model.PriceBook;
import cn.refinex.meridian.api.admin.model.PriceBookVersion;
import cn.refinex.meridian.api.admin.model.ProviderAccount;
import cn.refinex.meridian.api.admin.model.ProviderType;
import cn.refinex.meridian.api.admin.model.RateLimit;
import cn.refinex.meridian.api.admin.model.TokenPrices;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.controlplane.catalog.app.PriceBookService.PriceBookView;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.web.Times;
import java.math.BigDecimal;
import java.util.List;

/** 模型目录的 DTO 映射：生成的 DTO 与领域类型同名（{@code ProviderAccount}、{@code RateLimit}），领域类型用全限定名。 */
final class CatalogMapper {

    private CatalogMapper() {}

    static ProviderAccount toDto(cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount account) {
        return new ProviderAccount(
                account.id(),
                account.tenantId(),
                account.name(),
                ProviderType.fromValue(account.provider().value()),
                account.baseUrl(),
                account.secretId(),
                toDto(account.rateLimit()),
                Times.utc(account.createdAt()),
                Times.utc(account.updatedAt()));
    }

    static cn.refinex.meridian.api.admin.model.Model toDto(CatalogModel model) {
        return new cn.refinex.meridian.api.admin.model.Model(
                model.id(),
                model.tenantId(),
                model.providerAccountId(),
                model.name(),
                ModelKind.fromValue(model.kind().value()),
                model.upstreamModel(),
                model.contextWindow(),
                model.maxOutputTokens(),
                new ModelCapabilities(
                        model.capabilities().toolCalls(),
                        model.capabilities().vision(),
                        model.capabilities().reasoning()),
                model.fallbackModelId(),
                model.priceBookId(),
                model.baseUrl(),
                model.maxBatchSize(),
                toDto(model.rateLimit()),
                Times.utc(model.createdAt()),
                Times.utc(model.updatedAt()));
    }

    static PriceBook toDto(PriceBookView view) {
        return new PriceBook(
                view.book().id(),
                view.book().tenantId(),
                view.book().name(),
                view.book().currency(),
                toDto(view.currentVersion()),
                Times.utc(view.book().createdAt()),
                Times.utc(view.book().updatedAt()));
    }

    static PriceBookVersion toDto(cn.refinex.meridian.controlplane.catalog.domain.PriceBookVersion version) {
        var prices = version.prices();
        return new PriceBookVersion(
                version.priceBookId(),
                version.version(),
                new TokenPrices(
                        plain(prices.input()),
                        plain(prices.output()),
                        plain(prices.cacheRead()),
                        plain(prices.cacheWrite()),
                        plain(prices.reasoning())),
                Times.utc(version.effectiveFrom()),
                version.createdBy());
    }

    static RateLimit toDto(cn.refinex.meridian.controlplane.catalog.domain.RateLimit limit) {
        return new RateLimit(limit.rpm(), limit.tpm());
    }

    static cn.refinex.meridian.controlplane.catalog.domain.RateLimit fromDto(RateLimit limit) {
        return limit == null
                ? cn.refinex.meridian.controlplane.catalog.domain.RateLimit.NONE
                : new cn.refinex.meridian.controlplane.catalog.domain.RateLimit(limit.getRpm(), limit.getTpm());
    }

    static cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities fromDto(ModelCapabilities capabilities) {
        return capabilities == null
                ? cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities.NONE
                : new cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities(
                        capabilities.getToolCalls(), capabilities.getVision(), capabilities.getReasoning());
    }

    static cn.refinex.meridian.controlplane.catalog.domain.TokenPrices fromDto(TokenPrices prices) {
        try {
            return new cn.refinex.meridian.controlplane.catalog.domain.TokenPrices(
                    new BigDecimal(prices.getInput()),
                    new BigDecimal(prices.getOutput()),
                    new BigDecimal(prices.getCacheRead()),
                    new BigDecimal(prices.getCacheWrite()),
                    new BigDecimal(prices.getReasoning()));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "单价不合法",
                    List.of(new FieldViolation("prices", "单价是 0 到 10^12 之间、最多 6 位小数的十进制数")));
        }
    }

    /** 不带多余的尾零与指数写法：{@code 0.800000} → {@code 0.8}，{@code 2.000000} → {@code 2}。 */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
