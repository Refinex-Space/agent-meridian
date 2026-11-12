package cn.refinex.meridian.controlplane.catalog.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.ResolvedModel;
import cn.refinex.meridian.controlplane.catalog.infra.system.CatalogLookupRepository;
import org.springframework.stereotype.Service;

/**
 * 给 runtime 的模型解析（{@code GET /internal/v1/models/{model_id}}，ADR-0045 第 3 条）：模型、供应商账号（只有凭据 ID）与价格表当前
 * 版本。不按租户过滤，模型的租户由 runtime 与调用方比较。
 */
@Service
public class ModelCatalogQuery {

    private final CatalogLookupRepository lookup;

    /**
     * 创建查询。
     *
     * @param lookup 跨租户的模型查找
     */
    public ModelCatalogQuery(CatalogLookupRepository lookup) {
        this.lookup = lookup;
    }

    /**
     * 解析模型。
     *
     * @param modelId 模型 ID
     * @return 模型、账号与价格表当前版本
     * @throws NotFoundException 模型不存在时
     */
    public ResolvedModel resolve(String modelId) {
        return lookup.find(modelId)
                .orElseThrow(() -> new NotFoundException(CatalogErrorCode.MODEL_NOT_FOUND, "模型不存在：" + modelId));
    }
}
