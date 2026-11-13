package cn.refinex.meridian.controlplane.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 供应商账号（架构设计「数据架构」中的 {@code provider_accounts}）。
 *
 * @param id 供应商账号 ID（{@code prv_…}）；必填
 * @param tenantId 所属租户；必填
 * @param name 名称；必填
 * @param provider 类型；必填，创建后不能改
 * @param baseUrl 端点；为空表示该类型的官方端点
 * @param secretId 租户级凭据 ID；为空表示不需要凭据
 * @param rateLimit 账号级限流；必填（字段可为空）
 * @param createdBy 创建者；必填
 * @param createdAt 创建时间；必填
 * @param updatedAt 修改时间；必填
 * @param lastCheck 最近一次连通性测试的结果；从未测试过时为空（ADR-0053）
 */
public record ProviderAccount(
        String id,
        String tenantId,
        String name,
        ProviderType provider,
        String baseUrl,
        String secretId,
        RateLimit rateLimit,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        ConnectivityCheck lastCheck) {

    /**
     * 创建供应商账号。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ProviderAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 创建还没有连通性测试结果的账号。
     *
     * @param id 账号 ID
     * @param tenantId 所属租户
     * @param name 名称
     * @param provider 类型
     * @param baseUrl 端点；可为空
     * @param secretId 凭据 ID；可为空
     * @param rateLimit 限流
     * @param createdBy 创建者
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     */
    public ProviderAccount(
            String id,
            String tenantId,
            String name,
            ProviderType provider,
            String baseUrl,
            String secretId,
            RateLimit rateLimit,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
        this(id, tenantId, name, provider, baseUrl, secretId, rateLimit, createdBy, createdAt, updatedAt, null);
    }
}
