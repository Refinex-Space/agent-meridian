package cn.refinex.meridian.controlplane.secret.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.secret.domain.Secret;
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

/**
 * {@code cp.secrets} 的租户级仓储（凭据属于租户，可再限定到项目，ADR-0045 第 1 条）。密文只经 {@link #findStored} 读出，管理接口的
 * 查询不选密文列。
 */
@Repository
public class SecretRepository extends TenantScopedRepository {

    /** 管理查询的列：不含密文。 */
    private static final String METADATA =
            "id, tenant_id, project_id, name, version, created_by, created_at, updated_at, last_used_at";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public SecretRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入凭据。
     *
     * @param secret 元数据，租户必须等于上下文中的
     * @param ciphertext 密文
     */
    public void insert(Secret secret, byte[] ciphertext) {
        sql("""
                        INSERT INTO cp.secrets (id, tenant_id, project_id, name, version, ciphertext, created_by, created_at,
                                                updated_at)
                        VALUES (:id, :tenant_id, :project_filter, :name, :version, :ciphertext, :created_by, :created_at,
                                :updated_at)
                        """)
                .param("id", secret.id())
                .param("project_filter", secret.projectId())
                .param("name", secret.name())
                .param("version", secret.version())
                .param("ciphertext", ciphertext)
                .param("created_by", secret.createdBy())
                .param("created_at", secret.createdAt())
                .param("updated_at", secret.updatedAt())
                .update();
    }

    /**
     * 读取元数据。
     *
     * @param secretId 凭据 ID
     * @param forUpdate 是否加行锁
     * @return 元数据；不存在时为空
     */
    public Optional<Secret> find(String secretId, boolean forUpdate) {
        return sql("SELECT " + METADATA + " FROM cp.secrets WHERE tenant_id = :tenant_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", secretId)
                .query(SecretRepository::map)
                .optional();
    }

    /**
     * 读取元数据与密文：只有解析用例调用。
     *
     * @param secretId 凭据 ID
     * @return 元数据与密文；不存在时为空
     */
    public Optional<Stored> findStored(String secretId) {
        return sql("SELECT " + METADATA + ", ciphertext FROM cp.secrets WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", secretId)
                .query((rs, row) -> new Stored(map(rs, row), rs.getBytes("ciphertext")))
                .optional();
    }

    /**
     * 按 ID 升序列出当前租户的凭据（租户级与各项目的）。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Secret> list(PageRequest page) {
        return sql("SELECT " + METADATA + """
                         FROM cp.secrets
                         WHERE tenant_id = :tenant_id AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(SecretRepository::map)
                .list();
    }

    /**
     * 写入新版本的密文。
     *
     * @param secretId 凭据 ID
     * @param version 新版本号
     * @param ciphertext 以新版本号为附加认证数据加密的密文
     * @param now 当前时间
     */
    public void rotate(String secretId, int version, byte[] ciphertext, Instant now) {
        sql("""
                        UPDATE cp.secrets SET version = :version, ciphertext = :ciphertext, updated_at = :now
                         WHERE tenant_id = :tenant_id AND id = :id
                        """)
                .param("id", secretId)
                .param("version", version)
                .param("ciphertext", ciphertext)
                .param("now", now)
                .update();
    }

    /**
     * 删除凭据（连同密文）。
     *
     * @param secretId 凭据 ID
     */
    public void delete(String secretId) {
        sql("DELETE FROM cp.secrets WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", secretId)
                .update();
    }

    /**
     * 记录一次解析的时间。
     *
     * @param secretId 凭据 ID
     * @param now 当前时间
     */
    public void touch(String secretId, Instant now) {
        sql("UPDATE cp.secrets SET last_used_at = :now WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", secretId)
                .param("now", now)
                .update();
    }

    private static Secret map(ResultSet rs, int row) throws SQLException {
        return new Secret(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getInt("version"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                JdbcValues.instant(rs, "last_used_at"));
    }

    /**
     * 元数据与密文。
     *
     * @param secret 元数据
     * @param ciphertext 密文
     */
    public record Stored(Secret secret, byte[] ciphertext) {}
}
