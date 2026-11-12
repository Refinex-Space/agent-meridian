package cn.refinex.meridian.controlplane.catalog.api;

import cn.refinex.meridian.api.admin.model.CreateModelRequest;
import cn.refinex.meridian.api.admin.model.Model;
import cn.refinex.meridian.api.admin.model.ModelPage;
import cn.refinex.meridian.api.admin.model.UpdateModelRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.app.ModelService;
import cn.refinex.meridian.controlplane.catalog.app.ModelService.ModelDraft;
import cn.refinex.meridian.controlplane.catalog.domain.ModelKind;
import cn.refinex.meridian.controlplane.openapi.admin.ModelsApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 模型的管理接口（Models 标签，ADR-0045）：只做授权声明、decode → call → map。 */
@RestController
public class ModelsApiController implements ModelsApi {

    private final ModelService models;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param models 模型用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public ModelsApiController(ModelService models, AdminRequests requests, HttpServletRequest request) {
        this.models = models;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<Model> createModel(String tenantId, CreateModelRequest body, String idempotencyKey) {
        ModelDraft draft = new ModelDraft(
                body.getProviderAccountId(),
                body.getName(),
                ModelKind.fromValue(body.getKind().getValue()).orElseThrow(),
                body.getUpstreamModel(),
                body.getContextWindow(),
                body.getMaxOutputTokens(),
                CatalogMapper.fromDto(body.getCapabilities()),
                body.getFallbackModelId(),
                body.getPriceBookId(),
                body.getBaseUrl(),
                body.getMaxBatchSize(),
                CatalogMapper.fromDto(body.getRateLimit()));
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Model.class,
                () -> CatalogMapper.toDto(models.create(actor(), tenantId, draft)));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<ModelPage> listModels(String tenantId, Integer limit, String after) {
        var page = models.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new ModelPage(
                page.data().stream().map(CatalogMapper::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<Model> getModel(String tenantId, String modelId) {
        return ResponseEntity.ok(CatalogMapper.toDto(models.get(tenantId, modelId)));
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<Model> updateModel(
            String tenantId, String modelId, UpdateModelRequest body, String idempotencyKey) {
        ModelDraft draft = new ModelDraft(
                null,
                body.getName(),
                null,
                body.getUpstreamModel(),
                body.getContextWindow(),
                body.getMaxOutputTokens(),
                CatalogMapper.fromDto(body.getCapabilities()),
                body.getFallbackModelId(),
                body.getPriceBookId(),
                body.getBaseUrl(),
                body.getMaxBatchSize(),
                CatalogMapper.fromDto(body.getRateLimit()));
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Model.class,
                () -> CatalogMapper.toDto(models.update(actor(), tenantId, modelId, draft)));
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<Void> deleteModel(String tenantId, String modelId, String idempotencyKey) {
        return requests.delete(request, tenantId, idempotencyKey, () -> models.delete(actor(), tenantId, modelId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }
}
