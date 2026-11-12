package cn.refinex.meridian.controlplane.secret.infra;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.secret_data_keys} 的租户级仓储：每个租户一把包装后的数据密钥（ADR-0045 第 2 条）。 */
@Repository
public class SecretDataKeyRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public SecretDataKeyRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 读取当前租户的数据密钥。
     *
     * @return 包装后的数据密钥；还没有时为空
     */
    public Optional<WrappedDataKey> find() {
        return sql("SELECT * FROM cp.secret_data_keys WHERE tenant_id = :tenant_id")
                .query((rs, row) -> new WrappedDataKey(
                        rs.getString("key_encryption"),
                        rs.getString("kek_id"),
                        rs.getString("wrapped_key"),
                        JdbcValues.instant(rs, "created_at")))
                .optional();
    }

    /**
     * 写入当前租户的数据密钥；已有时不做任何事（并发生成时只留下一把）。
     *
     * @param key 包装后的数据密钥
     */
    public void insertIfAbsent(WrappedDataKey key) {
        sql("""
                        INSERT INTO cp.secret_data_keys (tenant_id, key_encryption, kek_id, wrapped_key, created_at)
                        VALUES (:tenant_id, :key_encryption, :kek_id, :wrapped_key, :created_at)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """)
                .param("key_encryption", key.keyEncryption())
                .param("kek_id", key.kekId())
                .param("wrapped_key", key.wrappedKey())
                .param("created_at", key.createdAt())
                .update();
    }

    /**
     * 包装后的数据密钥。
     *
     * @param keyEncryption 包装它的密钥加密服务类型
     * @param kekId 主密钥标识
     * @param wrappedKey 包装后的文本
     * @param createdAt 生成时间
     */
    public record WrappedDataKey(String keyEncryption, String kekId, String wrappedKey, Instant createdAt) {

        /**
         * 创建记录。
         *
         * @throws NullPointerException 缺少字段时
         */
        public WrappedDataKey {
            Objects.requireNonNull(keyEncryption, "keyEncryption");
            Objects.requireNonNull(kekId, "kekId");
            Objects.requireNonNull(wrappedKey, "wrappedKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
