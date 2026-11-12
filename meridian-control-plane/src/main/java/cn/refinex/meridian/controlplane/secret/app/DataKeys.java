package cn.refinex.meridian.controlplane.secret.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.secret.infra.SecretDataKeyRepository;
import cn.refinex.meridian.controlplane.secret.infra.SecretDataKeyRepository.WrappedDataKey;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 按租户的数据密钥（ADR-0045 第 2 条）：第一次需要时生成 AES-256 密钥、经 {@link KeyEncryptionService} 包装后存
 * {@code cp.secret_data_keys}；解包后的密钥在内存中最多缓存 {@code data-key-cache-ttl}（默认 5 分钟），避免每次解析都访问 Vault。
 *
 * <p>生成与写入在独立的事务（{@code REQUIRES_NEW}）中提交之后才放进缓存：否则外层事务回滚时，缓存里会留下一把库中没有的密钥，用它
 * 加密的凭据在缓存过期后再也解不开。两个请求同时为一个租户生成时，{@code ON CONFLICT DO NOTHING} 只留下一把，双方都以库中的为准。
 *
 * <p>线程安全：可被并发调用。
 */
@Component
public class DataKeys {

    private final SecretDataKeyRepository repository;
    private final KeyEncryptionService encryption;
    private final TransactionTemplate newTransaction;
    private final Duration cacheTtl;
    private final SecureRandom random;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 创建数据密钥管理。
     *
     * @param repository 数据密钥仓储
     * @param encryption 密钥加密服务
     * @param transactions 事务管理器，生成密钥用独立事务
     * @param properties 配置，取缓存时间
     * @param clock 时钟
     */
    public DataKeys(
            SecretDataKeyRepository repository,
            KeyEncryptionService encryption,
            PlatformTransactionManager transactions,
            ControlPlaneProperties properties,
            Clock clock) {
        this.repository = repository;
        this.encryption = encryption;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cacheTtl = properties.secrets().dataKeyCacheTtl();
        this.random = new SecureRandom();
        this.clock = clock;
    }

    /**
     * 取租户的数据密钥，没有时生成。
     *
     * @param tenantId 租户 ID
     * @return 数据密钥明文（32 字节）；调用方不能修改
     */
    public byte[] forTenant(String tenantId) {
        Instant now = clock.instant();
        Cached cached = cache.get(tenantId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.key();
        }
        WrappedDataKey stored = PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> newTransaction.execute(status -> repository.find().orElseGet(() -> generate(tenantId))));
        if (!stored.keyEncryption().equals(encryption.type())) {
            throw new MeridianException(
                    SecretErrorCode.ENCRYPTION_UNAVAILABLE,
                    "租户的数据密钥由 " + stored.keyEncryption() + " 包装，当前配置的密钥加密服务是 " + encryption.type());
        }
        byte[] key = encryption.unwrap(tenantId, stored.wrappedKey());
        if (key.length != SecretCipher.KEY_BYTES) {
            throw new MeridianException(SecretErrorCode.ENCRYPTION_UNAVAILABLE, "解包得到的数据密钥长度不正确");
        }
        cache.put(tenantId, new Cached(key, now.plus(cacheTtl)));
        return key;
    }

    /** 生成、包装、写入，再按库中的行返回（并发生成时只有一把写入成功）。 */
    private WrappedDataKey generate(String tenantId) {
        byte[] key = new byte[SecretCipher.KEY_BYTES];
        random.nextBytes(key);
        repository.insertIfAbsent(new WrappedDataKey(
                encryption.type(), encryption.keyId(), encryption.wrap(tenantId, key), clock.instant()));
        return repository.find().orElseThrow(() -> new IllegalStateException("数据密钥写入后读不到：" + tenantId));
    }

    /** 缓存的解包结果。 */
    private record Cached(byte[] key, Instant expiresAt) {}
}
