package cn.refinex.meridian.controlplane.catalog.domain;

import java.util.Objects;

/**
 * 内部解析接口给 runtime 的模型：模型、所属供应商账号与价格表当前版本（ADR-0045 第 3 条）。
 *
 * @param model 模型；必填
 * @param account 所属供应商账号；必填
 * @param priceBookVersion 价格表当前版本；没有价格表时为空
 */
public record ResolvedModel(CatalogModel model, ProviderAccount account, Integer priceBookVersion) {

    /**
     * 创建解析结果。
     *
     * @throws NullPointerException 缺少模型或账号时
     */
    public ResolvedModel {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(account, "account");
    }
}
