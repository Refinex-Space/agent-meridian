package cn.refinex.meridian.controlplane.catalog.api;

import cn.refinex.meridian.api.internal.model.CatalogModel;
import cn.refinex.meridian.api.internal.model.CatalogProviderAccount;
import cn.refinex.meridian.api.internal.model.CatalogRateLimit;
import cn.refinex.meridian.controlplane.catalog.app.ModelCatalogQuery;
import cn.refinex.meridian.controlplane.catalog.domain.RateLimit;
import cn.refinex.meridian.controlplane.catalog.domain.ResolvedModel;
import cn.refinex.meridian.controlplane.openapi.internal.InternalModelCatalogApi;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 模型解析的内部接口（ADR-0045 第 3 条）：只接受 runtime 的服务 JWT，响应不含凭据明文。 */
@RestController
public class InternalModelCatalogApiController implements InternalModelCatalogApi {

    private final ModelCatalogQuery query;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param query 解析用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalModelCatalogApiController(ModelCatalogQuery query, HttpServletRequest request) {
        this.query = query;
        this.request = request;
    }

    @Override
    public ResponseEntity<CatalogModel> getCatalogModel(String modelId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        ResolvedModel resolved = query.resolve(modelId);
        var model = resolved.model();
        var account = resolved.account();
        return ResponseEntity.ok(new CatalogModel(
                model.id(),
                model.tenantId(),
                model.kind().value(),
                model.upstreamModel(),
                model.contextWindow(),
                model.maxOutputTokens(),
                model.fallbackModelId(),
                model.baseUrl(),
                model.maxBatchSize(),
                toDto(model.rateLimit()),
                model.priceBookId(),
                resolved.priceBookVersion(),
                new CatalogProviderAccount(
                        account.id(),
                        account.provider().value(),
                        account.baseUrl(),
                        account.secretId(),
                        toDto(account.rateLimit()))));
    }

    private static CatalogRateLimit toDto(RateLimit limit) {
        return new CatalogRateLimit(limit.rpm(), limit.tpm());
    }
}
