package cn.refinex.meridian.controlplane.catalog.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBook;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBookVersion;
import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import cn.refinex.meridian.controlplane.catalog.infra.PriceBookRepository;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 价格表的管理用例（ADR-0045 第 8 条）：创建时发布版本 1；新版本在价格表行锁内递增版本号、立即生效，提交后经 {@code config.invalidate}
 * 通知 runtime，之后结算的用量记录带上新版本。版本不可修改、不可删除。
 */
@Service
public class PriceBookService {

    private final PriceBookRepository priceBooks;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param priceBooks 价格表仓储
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public PriceBookService(
            PriceBookRepository priceBooks,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.priceBooks = priceBooks;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建价格表并发布版本 1。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param name 名称
     * @param currency 币种
     * @param prices 版本 1 的单价
     * @return 价格表与当前版本
     */
    public PriceBookView create(String actor, String tenantId, String name, String currency, TokenPrices prices) {
        Instant now = clock.instant();
        PriceBook book = new PriceBook(Ids.newId(IdPrefix.PRICE_BOOK), tenantId, name, currency, 1, actor, now, now);
        PriceBookVersion version = new PriceBookVersion(book.id(), 1, prices, now, actor);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    try {
                        priceBooks.insert(book);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(CatalogErrorCode.NAME_TAKEN, "租户内已有名为 " + name + " 的价格表");
                    }
                    priceBooks.insertVersion(version);
                    audit.record(
                            "price_book.created",
                            actor,
                            tenantId,
                            null,
                            "price_book",
                            book.id(),
                            details(book, version));
                    return new PriceBookView(book, version);
                }));
    }

    /**
     * 发布新版本：立即生效。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 价格表 ID
     * @param prices 新版本的单价
     * @return 新版本
     */
    public PriceBookVersion publish(String actor, String tenantId, String id, TokenPrices prices) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    PriceBook book = require(id, true);
                    Instant now = clock.instant();
                    PriceBookVersion version = new PriceBookVersion(id, book.currentVersion() + 1, prices, now, actor);
                    priceBooks.insertVersion(version);
                    priceBooks.advance(id, version.version(), now);
                    audit.record(
                            "price_book.version_published",
                            actor,
                            tenantId,
                            null,
                            "price_book",
                            id,
                            details(book, version));
                    invalidations.resourceChanged(ConfigInvalidations.PRICE_BOOK, tenantId, id);
                    return version;
                }));
    }

    /**
     * 读取价格表与当前版本。
     *
     * @param tenantId 租户 ID
     * @param id 价格表 ID
     * @return 价格表与当前版本
     */
    public PriceBookView get(String tenantId, String id) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> view(require(id, false)));
    }

    /**
     * 列出价格表。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页价格表与当前版本
     */
    public PageResult<PriceBookView> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> {
            PageResult<PriceBook> books = PageResult.of(priceBooks.list(page), page, b -> Cursor.of(b.id()));
            return new PageResult<>(
                    books.data().stream().map(this::view).toList(), books.hasMore(), books.nextCursor());
        });
    }

    /**
     * 列出版本，版本号从新到旧。
     *
     * @param tenantId 租户 ID
     * @param id 价格表 ID
     * @param page 分页参数
     * @return 一页版本
     */
    public PageResult<PriceBookVersion> versions(String tenantId, String id, PageRequest page) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> {
            require(id, false);
            return PageResult.of(priceBooks.listVersions(id, page), page, v -> Cursor.of(String.valueOf(v.version())));
        });
    }

    private PriceBookView view(PriceBook book) {
        return new PriceBookView(
                book,
                priceBooks
                        .findVersion(book.id(), book.currentVersion())
                        .orElseThrow(() -> new IllegalStateException("价格表缺少当前版本：" + book.id())));
    }

    private PriceBook require(String id, boolean forUpdate) {
        return priceBooks
                .find(id, forUpdate)
                .orElseThrow(() -> new NotFoundException(CatalogErrorCode.PRICE_BOOK_NOT_FOUND, "价格表不存在：" + id));
    }

    private static Map<String, Object> details(PriceBook book, PriceBookVersion version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", book.name());
        details.put("currency", book.currency());
        details.put("version", version.version());
        return details;
    }

    /**
     * 价格表与当前版本。
     *
     * @param book 价格表
     * @param currentVersion 当前版本
     */
    public record PriceBookView(PriceBook book, PriceBookVersion currentVersion) {}
}
