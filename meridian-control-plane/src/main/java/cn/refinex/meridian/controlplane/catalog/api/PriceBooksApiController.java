package cn.refinex.meridian.controlplane.catalog.api;

import cn.refinex.meridian.api.admin.model.CreatePriceBookRequest;
import cn.refinex.meridian.api.admin.model.PriceBook;
import cn.refinex.meridian.api.admin.model.PriceBookPage;
import cn.refinex.meridian.api.admin.model.PriceBookVersion;
import cn.refinex.meridian.api.admin.model.PriceBookVersionPage;
import cn.refinex.meridian.api.admin.model.PublishPriceBookVersionRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.app.PriceBookService;
import cn.refinex.meridian.controlplane.openapi.admin.PriceBooksApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 价格表的管理接口（PriceBooks 标签，ADR-0045）：只做授权声明、decode → call → map。 */
@RestController
public class PriceBooksApiController implements PriceBooksApi {

    private final PriceBookService priceBooks;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param priceBooks 价格表用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public PriceBooksApiController(PriceBookService priceBooks, AdminRequests requests, HttpServletRequest request) {
        this.priceBooks = priceBooks;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<PriceBook> createPriceBook(
            String tenantId, CreatePriceBookRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                PriceBook.class,
                () -> CatalogMapper.toDto(priceBooks.create(
                        actor(),
                        tenantId,
                        body.getName(),
                        body.getCurrency(),
                        CatalogMapper.fromDto(body.getPrices()))));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<PriceBookPage> listPriceBooks(String tenantId, Integer limit, String after) {
        var page = priceBooks.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new PriceBookPage(
                page.data().stream().map(CatalogMapper::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<PriceBook> getPriceBook(String tenantId, String priceBookId) {
        return ResponseEntity.ok(CatalogMapper.toDto(priceBooks.get(tenantId, priceBookId)));
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<PriceBookVersion> publishPriceBookVersion(
            String tenantId, String priceBookId, PublishPriceBookVersionRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                PriceBookVersion.class,
                () -> CatalogMapper.toDto(
                        priceBooks.publish(actor(), tenantId, priceBookId, CatalogMapper.fromDto(body.getPrices()))));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<PriceBookVersionPage> listPriceBookVersions(
            String tenantId, String priceBookId, Integer limit, String after) {
        var page = priceBooks.versions(tenantId, priceBookId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new PriceBookVersionPage(
                page.data().stream().map(CatalogMapper::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }
}
