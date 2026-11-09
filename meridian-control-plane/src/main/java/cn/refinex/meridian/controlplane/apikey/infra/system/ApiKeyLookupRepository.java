package cn.refinex.meridian.controlplane.apikey.infra.system;

import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.apikey.infra.ApiKeyRepository;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 按哈希查找有效的 API Key：内部校验接口使用，请求到达时还不知道租户。 */
@Repository
@CrossTenant(reason = "认证查找：按 API Key 的 SHA-256 找到它所属的租户与项目（架构设计「租户隔离」中 SystemRepository 的用途）")
public class ApiKeyLookupRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ApiKeyLookupRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 查找有效的 Key：未吊销、未过期，所属项目与租户都未删除。
     *
     * @param keyHash 明文的 SHA-256
     * @param now 当前时间
     * @return 元数据；无效时为空
     */
    public Optional<ApiKey> findValid(String keyHash, Instant now) {
        return jdbc().sql("""
                        SELECT k.* FROM cp.api_keys k
                          JOIN cp.projects p ON p.id = k.project_id AND p.deleted_at IS NULL
                          JOIN cp.tenants t ON t.id = k.tenant_id AND t.deleted_at IS NULL
                         WHERE k.key_hash = :hash AND k.revoked_at IS NULL
                           AND (k.expires_at IS NULL OR k.expires_at > :now)
                        """)
                .param("hash", keyHash)
                .param("now", JdbcValues.timestamptz(now))
                .query(ApiKeyRepository::map)
                .optional();
    }
}
