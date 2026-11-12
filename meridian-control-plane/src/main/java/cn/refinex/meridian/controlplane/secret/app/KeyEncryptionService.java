package cn.refinex.meridian.controlplane.secret.app;

/**
 * 密钥加密服务：用只在密钥管理系统中的主密钥（KEK）包装与解包按租户的数据密钥（架构设计「凭据」，ADR-0045 第 2 条）。
 *
 * <p>实现：{@code infra.LocalMasterKeyEncryption}（开发与测试，主密钥在配置给出的本地文件中）、{@code infra.VaultTransitKeyEncryption}
 * （HashiCorp Vault Transit）。失败（服务不可用、密文无法解包）时以 {@code secret.encryption_unavailable}（503）的平台异常失败。
 *
 * <p>线程安全：实现可被并发调用。
 */
public interface KeyEncryptionService {

    /**
     * 实现的类型，写入 {@code cp.secret_data_keys.key_encryption}：解包时类型不符说明配置换过，不能用当前服务解包。
     *
     * @return {@code local} 或 {@code vault}
     */
    String type();

    /**
     * 主密钥的标识，写入 {@code cp.secret_data_keys.kek_id}，用于排查；不含任何密钥材料。
     *
     * @return 标识
     */
    String keyId();

    /**
     * 包装一个数据密钥。
     *
     * @param tenantId 数据密钥所属的租户：本地实现用作附加认证数据，Vault 实现不使用（Transit 的普通密钥不支持）
     * @param dataKey 数据密钥明文
     * @return 包装后的文本，原样保存
     */
    String wrap(String tenantId, byte[] dataKey);

    /**
     * 解包一个数据密钥。
     *
     * @param tenantId 数据密钥所属的租户
     * @param wrapped {@link #wrap} 的结果
     * @return 数据密钥明文
     */
    byte[] unwrap(String tenantId, String wrapped);
}
