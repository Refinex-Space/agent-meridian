package cn.refinex.meridian.controlplane.catalog.infra;

import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import cn.refinex.meridian.controlplane.catalog.domain.RateLimit;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.provider_accounts} 的租户级仓储（供应商账号是租户级数据，架构设计「租户隔离」）。 */
@Repository
public class ProviderAccountRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ProviderAccountRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入供应商账号。
     *
     * @param account 账号，租户必须等于上下文中的
     */
    public void insert(ProviderAccount account) {
        sql("""
                        INSERT INTO cp.provider_accounts (id, tenant_id, name, provider, base_url, secret_id, rpm, tpm,
                                                          created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :name, :provider, :base_url, :secret_id, :rpm, :tpm,
                                :created_by, :created_at, :updated_at)
                        """)
                .param("id", account.id())
                .param("name", account.name())
                .param("provider", account.provider().value())
                .param("base_url", account.baseUrl())
                .param("secret_id", account.secretId())
                .param("rpm", account.rateLimit().rpm())
                .param("tpm", account.rateLimit().tpm())
                .param("created_by", account.createdBy())
                .param("created_at", account.createdAt())
                .param("updated_at", account.updatedAt())
                .update();
    }

    /**
     * 读取供应商账号。
     *
     * @param id 账号 ID
     * @param forUpdate 是否加行锁
     * @return 账号；不存在时为空
     */
    public Optional<ProviderAccount> find(String id, boolean forUpdate) {
        return sql("SELECT * FROM cp.provider_accounts WHERE tenant_id = :tenant_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(ProviderAccountRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<ProviderAccount> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.provider_accounts
                         WHERE tenant_id = :tenant_id AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(ProviderAccountRepository::map)
                .list();
    }

    /**
     * 整体替换可改的字段。
     *
     * @param account 修改后的账号
     */
    public void update(ProviderAccount account) {
        sql("""
                        UPDATE cp.provider_accounts
                           SET name = :name, base_url = :base_url, secret_id = :secret_id, rpm = :rpm, tpm = :tpm,
                               updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND id = :id
                        """)
                .param("id", account.id())
                .param("name", account.name())
                .param("base_url", account.baseUrl())
                .param("secret_id", account.secretId())
                .param("rpm", account.rateLimit().rpm())
                .param("tpm", account.rateLimit().tpm())
                .param("updated_at", account.updatedAt())
                .update();
    }

    /**
     * 删除供应商账号。
     *
     * @param id 账号 ID
     */
    public void delete(String id) {
        sql("DELETE FROM cp.provider_accounts WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 账号下的模型：删除账号时的引用方。
     *
     * @param id 账号 ID
     * @return 引用方，按 ID 排序
     */
    public List<ResourceReference> models(String id) {
        return sql("""
                        SELECT id, name FROM cp.models
                         WHERE tenant_id = :tenant_id AND provider_account_id = :account ORDER BY id
                        """)
                .param("account", id)
                .query((rs, row) -> new ResourceReference("model", rs.getString("id"), rs.getString("name")))
                .list();
    }

    /**
     * 把一行映射为账号；跨租户的查找共用。
     *
     * @param rs 结果集，当前行是 {@code cp.provider_accounts} 的整行
     * @param row 行号
     * @return 账号
     * @throws SQLException 读取失败时
     */
    public static ProviderAccount map(ResultSet rs, int row) throws SQLException {
        return new ProviderAccount(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                ProviderType.fromValue(rs.getString("provider")).orElseThrow(),
                rs.getString("base_url"),
                rs.getString("secret_id"),
                rateLimit(rs, "rpm", "tpm"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                ConnectivityCheckJson.fromJson(rs.getString("last_check")));
    }

    /**
     * 记下最近一次连通性测试的结果（ADR-0053）。
     *
     * @param id 账号 ID
     * @param check 结果
     */
    public void saveCheck(String id, ConnectivityCheck check) {
        sql("UPDATE cp.provider_accounts SET last_check = :check WHERE tenant_id = :tenant_id AND id = :id")
                .param("check", ConnectivityCheckJson.toJsonb(check))
                .param("id", id)
                .update();
    }

    /**
     * 读取可空的限流列。
     *
     * @param rs 结果集
     * @param rpmColumn RPM 列名
     * @param tpmColumn TPM 列名
     * @return 限流参数
     * @throws SQLException 读取失败时
     */
    public static RateLimit rateLimit(ResultSet rs, String rpmColumn, String tpmColumn) throws SQLException {
        return new RateLimit(rs.getObject(rpmColumn, Integer.class), rs.getObject(tpmColumn, Integer.class));
    }
}
