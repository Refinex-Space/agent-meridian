package cn.refinex.meridian.security.oidc;

import java.util.Objects;
import java.util.Set;

/**
 * 认证后的控制台用户：身份提供方中的用户标识（默认取 OIDC 的 {@code sub}，可配置为其他声明）与令牌中的组。租户内的角色不来自令牌，
 * 由 control-plane 按用户标识查角色绑定（ADR-0042）；组只用于按组判定平台管理员（ADR-0043）；经过验证的邮箱只用于首次登录时绑定
 * 按邮箱邀请的成员（ADR-0053）。
 *
 * @param subject 用户标识；必填，在身份提供方内唯一且不变
 * @param groups 令牌中的组；可为空集合
 * @param verifiedEmail 令牌中经过验证的邮箱；令牌没有邮箱或邮箱未验证时为空
 */
public record ConsoleUser(String subject, Set<String> groups, String verifiedEmail) {

    /**
     * 创建用户。
     *
     * @throws IllegalArgumentException 用户标识为空时
     */
    public ConsoleUser {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("控制台用户的主体标识不能为空");
        }
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        verifiedEmail = verifiedEmail == null || verifiedEmail.isBlank() ? null : verifiedEmail.strip();
    }

    /**
     * 创建不带组的用户。
     *
     * @param subject 用户标识
     */
    public ConsoleUser(String subject) {
        this(subject, Set.of(), null);
    }

    /**
     * 创建不带邮箱的用户。
     *
     * @param subject 用户标识
     * @param groups 令牌中的组
     */
    public ConsoleUser(String subject, Set<String> groups) {
        this(subject, groups, null);
    }
}
