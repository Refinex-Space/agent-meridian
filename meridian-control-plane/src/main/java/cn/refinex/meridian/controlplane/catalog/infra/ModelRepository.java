package cn.refinex.meridian.controlplane.catalog.infra;

import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities;
import cn.refinex.meridian.controlplane.catalog.domain.ModelKind;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.models} 的租户级仓储：模型属于租户，租户内的全部项目都可以使用（ADR-0045 第 1 条）。 */
@Repository
public class ModelRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ModelRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入模型。
     *
     * @param model 模型，租户必须等于上下文中的
     */
    public void insert(CatalogModel model) {
        sql("""
                        INSERT INTO cp.models (id, tenant_id, provider_account_id, name, kind, upstream_model, context_window,
                                               max_output_tokens, supports_tool_calls, supports_vision, supports_reasoning,
                                               fallback_model_id, price_book_id, base_url, max_batch_size, rpm, tpm,
                                               created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :account, :name, :kind, :upstream_model, :context_window,
                                :max_output_tokens, :tool_calls, :vision, :reasoning,
                                :fallback, :price_book, :base_url, :max_batch_size, :rpm, :tpm,
                                :created_by, :created_at, :updated_at)
                        """)
                .param("id", model.id())
                .param("account", model.providerAccountId())
                .param("name", model.name())
                .param("kind", model.kind().value())
                .param("upstream_model", model.upstreamModel())
                .param("context_window", model.contextWindow())
                .param("max_output_tokens", model.maxOutputTokens())
                .param("tool_calls", model.capabilities().toolCalls())
                .param("vision", model.capabilities().vision())
                .param("reasoning", model.capabilities().reasoning())
                .param("fallback", model.fallbackModelId())
                .param("price_book", model.priceBookId())
                .param("base_url", model.baseUrl())
                .param("max_batch_size", model.maxBatchSize())
                .param("rpm", model.rateLimit().rpm())
                .param("tpm", model.rateLimit().tpm())
                .param("created_by", model.createdBy())
                .param("created_at", model.createdAt())
                .param("updated_at", model.updatedAt())
                .update();
    }

    /**
     * 读取模型。
     *
     * @param id 模型 ID
     * @param forUpdate 是否加行锁
     * @return 模型；不存在时为空
     */
    public Optional<CatalogModel> find(String id, boolean forUpdate) {
        return sql("SELECT * FROM cp.models WHERE tenant_id = :tenant_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(ModelRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<CatalogModel> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.models
                         WHERE tenant_id = :tenant_id AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(ModelRepository::map)
                .list();
    }

    /**
     * 整体替换可改的字段（供应商账号与种类不变）。
     *
     * @param model 修改后的模型
     */
    public void update(CatalogModel model) {
        sql("""
                        UPDATE cp.models
                           SET name = :name, upstream_model = :upstream_model, context_window = :context_window,
                               max_output_tokens = :max_output_tokens, supports_tool_calls = :tool_calls,
                               supports_vision = :vision, supports_reasoning = :reasoning, fallback_model_id = :fallback,
                               price_book_id = :price_book, base_url = :base_url, max_batch_size = :max_batch_size,
                               rpm = :rpm, tpm = :tpm, updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND id = :id
                        """)
                .param("id", model.id())
                .param("name", model.name())
                .param("upstream_model", model.upstreamModel())
                .param("context_window", model.contextWindow())
                .param("max_output_tokens", model.maxOutputTokens())
                .param("tool_calls", model.capabilities().toolCalls())
                .param("vision", model.capabilities().vision())
                .param("reasoning", model.capabilities().reasoning())
                .param("fallback", model.fallbackModelId())
                .param("price_book", model.priceBookId())
                .param("base_url", model.baseUrl())
                .param("max_batch_size", model.maxBatchSize())
                .param("rpm", model.rateLimit().rpm())
                .param("tpm", model.rateLimit().tpm())
                .param("updated_at", model.updatedAt())
                .update();
    }

    /**
     * 删除模型。
     *
     * @param id 模型 ID
     */
    public void delete(String id) {
        sql("DELETE FROM cp.models WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 以它为备用模型的其他模型：删除模型时的引用方。
     *
     * @param id 模型 ID
     * @return 引用方，按 ID 排序
     */
    public List<ResourceReference> fallbackReferences(String id) {
        return sql("""
                        SELECT id, name FROM cp.models
                         WHERE tenant_id = :tenant_id AND fallback_model_id = :model ORDER BY id
                        """)
                .param("model", id)
                .query((rs, row) -> new ResourceReference("model", rs.getString("id"), rs.getString("name")))
                .list();
    }

    /**
     * 引用某个价格表的模型 ID：发布新版本时只影响它们。
     *
     * @param priceBookId 价格表 ID
     * @return 模型 ID
     */
    public List<String> idsWithPriceBook(String priceBookId) {
        return sql("SELECT id FROM cp.models WHERE tenant_id = :tenant_id AND price_book_id = :price_book ORDER BY id")
                .param("price_book", priceBookId)
                .query(String.class)
                .list();
    }

    /**
     * 把一行映射为模型；跨租户的查找共用。
     *
     * @param rs 结果集，当前行是 {@code cp.models} 的整行
     * @param row 行号
     * @return 模型
     * @throws SQLException 读取失败时
     */
    public static CatalogModel map(ResultSet rs, int row) throws SQLException {
        return new CatalogModel(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("provider_account_id"),
                rs.getString("name"),
                ModelKind.fromValue(rs.getString("kind")).orElseThrow(),
                rs.getString("upstream_model"),
                rs.getInt("context_window"),
                rs.getInt("max_output_tokens"),
                new ModelCapabilities(
                        rs.getBoolean("supports_tool_calls"),
                        rs.getBoolean("supports_vision"),
                        rs.getBoolean("supports_reasoning")),
                rs.getString("fallback_model_id"),
                rs.getString("price_book_id"),
                rs.getString("base_url"),
                rs.getObject("max_batch_size", Integer.class),
                ProviderAccountRepository.rateLimit(rs, "rpm", "tpm"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
