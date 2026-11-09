package cn.refinex.meridian.controlplane.apikey.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code cp.api_keys} 的项目级仓储：租户与项目都取自 {@code PlatformContext}（API Key 挂在项目下，架构设计「租户层级」）。
 * 哈希只写不读：管理接口从不返回它。
 */
@Repository
public class ApiKeyRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ApiKeyRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入 API Key。
     *
     * @param key 元数据，租户与项目必须等于上下文中的
     * @param keyHash 明文的 SHA-256
     */
    public void insert(ApiKey key, String keyHash) {
        sql("""
                        INSERT INTO cp.api_keys (id, tenant_id, project_id, name, key_hash, key_prefix, scopes,
                                                 rate_limit_per_minute, expires_at, created_by, created_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :hash, :prefix, :scopes,
                                :rate_limit, :expires_at, :created_by, :created_at)
                        """)
                .param("id", key.id())
                .param("name", key.name())
                .param("hash", keyHash)
                .param("prefix", key.keyPrefix())
                .param(
                        "scopes",
                        JdbcValues.textArray(key.scopes().stream()
                                .map(ApiKeyScope::value)
                                .sorted()
                                .toList()))
                .param("rate_limit", key.rateLimitPerMinute())
                .param("expires_at", key.expiresAt())
                .param("created_by", key.createdBy())
                .param("created_at", key.createdAt())
                .update();
    }

    /**
     * 读取 API Key。
     *
     * @param keyId API Key ID
     * @param forUpdate 是否加行锁
     * @return 元数据；不存在时为空
     */
    public Optional<ApiKey> find(String keyId, boolean forUpdate) {
        return sql("SELECT * FROM cp.api_keys WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", keyId)
                .query(ApiKeyRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出 API Key，包括已吊销的。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<ApiKey> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.api_keys
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(ApiKeyRepository::map)
                .list();
    }

    /**
     * 修改名称。
     *
     * @param keyId API Key ID
     * @param name 新名称
     */
    public void rename(String keyId, String name) {
        sql("UPDATE cp.api_keys SET name = :name WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", keyId)
                .param("name", name)
                .update();
    }

    /**
     * 吊销一个尚未吊销的 Key。
     *
     * @param keyId API Key ID
     * @param now 当前时间
     * @return 本次吊销时为 true；已吊销时为 false
     */
    public boolean revoke(String keyId, Instant now) {
        return sql("""
                        UPDATE cp.api_keys SET revoked_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND revoked_at IS NULL
                        """).param("id", keyId).param("now", now).update() == 1;
    }

    /**
     * 吊销当前项目全部尚未吊销的 Key（删除项目、租户时）。
     *
     * @param now 当前时间
     * @return 本次吊销的 Key
     */
    public List<ApiKey> revokeAll(Instant now) {
        return sql("""
                        UPDATE cp.api_keys SET revoked_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND revoked_at IS NULL
                        RETURNING *
                        """).param("now", now).query(ApiKeyRepository::map).list();
    }

    /**
     * 把一行映射为元数据；校验查找共用。
     *
     * @param rs 结果集，当前行是 {@code cp.api_keys} 的整行
     * @param row 行号
     * @return 元数据
     * @throws SQLException 读取失败时
     */
    public static ApiKey map(ResultSet rs, int row) throws SQLException {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        for (String scope : JdbcValues.readTextArray(rs, "scopes")) {
            ApiKeyScope.fromValue(scope).ifPresent(scopes::add);
        }
        return new ApiKey(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("key_prefix"),
                scopes,
                rs.getInt("rate_limit_per_minute"),
                JdbcValues.instant(rs, "expires_at"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "revoked_at"));
    }
}
