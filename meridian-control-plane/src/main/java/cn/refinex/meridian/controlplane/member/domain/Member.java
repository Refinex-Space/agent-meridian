package cn.refinex.meridian.controlplane.member.domain;

import java.time.Instant;

/**
 * 租户成员：按身份提供方的主体标识识别（ADR-0042）。成员本身没有权限，权限来自角色绑定。
 *
 * @param id 成员 ID（{@code mbr_…}）
 * @param tenantId 所属租户 ID
 * @param subject 主体标识（OIDC 的 {@code sub}），租户内唯一
 * @param email 邮箱；空字符串表示没有
 * @param displayName 显示名称；空字符串表示没有
 * @param createdAt 加入时间
 * @param updatedAt 最近一次修改时间
 */
public record Member(
        String id,
        String tenantId,
        String subject,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {}
