package cn.refinex.meridian.controlplane.member.domain;

import java.time.Instant;

/**
 * 租户成员：按身份提供方的主体标识识别（ADR-0042）。成员本身没有权限，权限来自角色绑定。只有邮箱、没有主体标识的成员是邀请，
 * 用户第一次登录时按令牌中经过验证的邮箱绑定（ADR-0053）。
 *
 * @param id 成员 ID（{@code mbr_…}）
 * @param tenantId 所属租户 ID
 * @param subject 主体标识（OIDC 的用户标识声明），租户内唯一；邀请尚未绑定时为空
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
        Instant updatedAt) {

    /**
     * 是否为尚未绑定的邀请。
     *
     * @return 没有主体标识时为 true
     */
    public boolean invited() {
        return subject == null;
    }
}
