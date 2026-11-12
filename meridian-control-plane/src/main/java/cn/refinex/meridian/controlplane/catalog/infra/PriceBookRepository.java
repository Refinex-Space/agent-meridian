package cn.refinex.meridian.controlplane.catalog.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBook;
import cn.refinex.meridian.controlplane.catalog.domain.PriceBookVersion;
import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.price_books} 与 {@code cp.price_book_versions} 的租户级仓储：版本只插入，不更新、不删除（ADR-0045 第 8 条）。 */
@Repository
public class PriceBookRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public PriceBookRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入价格表（不含版本）。
     *
     * @param book 价格表，租户必须等于上下文中的
     */
    public void insert(PriceBook book) {
        sql("""
                        INSERT INTO cp.price_books (id, tenant_id, name, currency, current_version, created_by, created_at,
                                                    updated_at)
                        VALUES (:id, :tenant_id, :name, :currency, :current_version, :created_by, :created_at, :updated_at)
                        """)
                .param("id", book.id())
                .param("name", book.name())
                .param("currency", book.currency())
                .param("current_version", book.currentVersion())
                .param("created_by", book.createdBy())
                .param("created_at", book.createdAt())
                .param("updated_at", book.updatedAt())
                .update();
    }

    /**
     * 插入一个版本。
     *
     * @param version 版本
     */
    public void insertVersion(PriceBookVersion version) {
        sql("""
                        INSERT INTO cp.price_book_versions (price_book_id, version, tenant_id, input_price, output_price,
                                                            cache_read_price, cache_write_price, reasoning_price,
                                                            effective_from, created_by)
                        VALUES (:price_book, :version, :tenant_id, :input, :output, :cache_read, :cache_write, :reasoning,
                                :effective_from, :created_by)
                        """)
                .param("price_book", version.priceBookId())
                .param("version", version.version())
                .param("input", version.prices().input())
                .param("output", version.prices().output())
                .param("cache_read", version.prices().cacheRead())
                .param("cache_write", version.prices().cacheWrite())
                .param("reasoning", version.prices().reasoning())
                .param("effective_from", version.effectiveFrom())
                .param("created_by", version.createdBy())
                .update();
    }

    /**
     * 把当前版本推进到新发布的版本。
     *
     * @param id 价格表 ID
     * @param version 新版本号
     * @param now 发布时间
     */
    public void advance(String id, int version, Instant now) {
        sql("""
                        UPDATE cp.price_books SET current_version = :version, updated_at = :now
                         WHERE tenant_id = :tenant_id AND id = :id
                        """).param("id", id).param("version", version).param("now", now).update();
    }

    /**
     * 读取价格表。
     *
     * @param id 价格表 ID
     * @param forUpdate 是否加行锁（发布版本时串行）
     * @return 价格表；不存在时为空
     */
    public Optional<PriceBook> find(String id, boolean forUpdate) {
        return sql("SELECT * FROM cp.price_books WHERE tenant_id = :tenant_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(PriceBookRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<PriceBook> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.price_books
                         WHERE tenant_id = :tenant_id AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(PriceBookRepository::map)
                .list();
    }

    /**
     * 读取一个版本。
     *
     * @param id 价格表 ID
     * @param version 版本号
     * @return 版本；不存在时为空
     */
    public Optional<PriceBookVersion> findVersion(String id, int version) {
        return sql("""
                        SELECT * FROM cp.price_book_versions
                         WHERE tenant_id = :tenant_id AND price_book_id = :price_book AND version = :version
                        """)
                .param("price_book", id)
                .param("version", version)
                .query(PriceBookRepository::mapVersion)
                .optional();
    }

    /**
     * 按版本号从新到旧列出版本。
     *
     * @param id 价格表 ID
     * @param page 分页参数，游标是版本号
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<PriceBookVersion> listVersions(String id, PageRequest page) {
        return sql("""
                        SELECT * FROM cp.price_book_versions
                         WHERE tenant_id = :tenant_id AND price_book_id = :price_book
                           AND (CAST(:before AS integer) IS NULL OR version < :before)
                         ORDER BY version DESC LIMIT :limit
                        """)
                .param("price_book", id)
                .param(
                        "before",
                        page.after() == null
                                ? null
                                : Integer.valueOf(page.after().key(0)))
                .param("limit", page.fetchSize())
                .query(PriceBookRepository::mapVersion)
                .list();
    }

    private static PriceBook map(ResultSet rs, int row) throws SQLException {
        return new PriceBook(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getInt("current_version"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }

    private static PriceBookVersion mapVersion(ResultSet rs, int row) throws SQLException {
        return new PriceBookVersion(
                rs.getString("price_book_id"),
                rs.getInt("version"),
                new TokenPrices(
                        rs.getBigDecimal("input_price"),
                        rs.getBigDecimal("output_price"),
                        rs.getBigDecimal("cache_read_price"),
                        rs.getBigDecimal("cache_write_price"),
                        rs.getBigDecimal("reasoning_price")),
                JdbcValues.instant(rs, "effective_from"),
                rs.getString("created_by"));
    }
}
