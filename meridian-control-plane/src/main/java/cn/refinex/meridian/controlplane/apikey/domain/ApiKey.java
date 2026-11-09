package cn.refinex.meridian.controlplane.apikey.domain;

import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.time.Instant;
import java.util.Set;

/**
 * API Key 的元数据，不含明文与哈希（架构设计「认证」，ADR-0042）。
 *
 * @param id API Key ID（{@code key_…}）
 * @param tenantId 所属租户 ID
 * @param projectId 所属项目 ID
 * @param name 名称
 * @param keyPrefix 明文中 {@code mk_live_} 之后的前 8 位
 * @param scopes 作用域
 * @param rateLimitPerMinute 每分钟请求上限
 * @param expiresAt 过期时间；可为空，表示不过期
 * @param createdBy 创建者的主体标识
 * @param createdAt 创建时间
 * @param revokedAt 吊销时间；可为空，表示未吊销
 */
public record ApiKey(
        String id,
        String tenantId,
        String projectId,
        String name,
        String keyPrefix,
        Set<ApiKeyScope> scopes,
        int rateLimitPerMinute,
        Instant expiresAt,
        String createdBy,
        Instant createdAt,
        Instant revokedAt) {

    /** 规整作用域集合。 */
    public ApiKey {
        scopes = Set.copyOf(scopes);
    }

    /**
     * 展示用的前缀：{@code mk_live_} 加随机部分的前 8 位，日志、审计与列表只出现它。
     *
     * @return 例如 {@code mk_live_AbCd1234}
     */
    public String displayPrefix() {
        return ApiKeySecret.PREFIX + keyPrefix;
    }

    /**
     * 某一时刻的状态。
     *
     * @param now 当前时间
     * @return 已吊销优先于已过期
     */
    public ApiKeyStatus status(Instant now) {
        if (revokedAt != null) {
            return ApiKeyStatus.REVOKED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return ApiKeyStatus.EXPIRED;
        }
        return ApiKeyStatus.ACTIVE;
    }
}
