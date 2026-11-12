package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.Objects;

/**
 * 模型目录的一项：模型与它的供应商账号，一次读出、一起缓存，失效通知不会让二者错开（ADR-0045 第 4 条）。
 *
 * @param model 模型；必填
 * @param account 所属供应商账号；必填
 */
public record CatalogEntry(CatalogModel model, ProviderAccount account) {

    /**
     * 创建目录项。
     *
     * @throws NullPointerException 缺少模型或账号时
     * @throws IllegalArgumentException 账号不是模型所属的账号时
     */
    public CatalogEntry {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(account, "account");
        if (!model.providerAccountId().equals(account.providerAccountId())) {
            throw new IllegalArgumentException("模型 " + model.modelId() + " 不属于供应商账号 " + account.providerAccountId());
        }
    }

    /**
     * 本模型的端点：模型自己的覆盖账号的。
     *
     * @return 端点；可为空，表示该类型的默认端点
     */
    public String baseUrl() {
        return model.baseUrl() != null ? model.baseUrl() : account.baseUrl();
    }

    /**
     * 本模型的限流参数：模型自己的，没有时取账号的。
     *
     * @return 限流参数
     */
    public RateLimit rateLimit() {
        return model.rateLimit() != null ? model.rateLimit() : account.rateLimit();
    }
}
