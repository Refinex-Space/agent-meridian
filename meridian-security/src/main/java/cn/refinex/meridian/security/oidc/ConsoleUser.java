package cn.refinex.meridian.security.oidc;

import java.util.Objects;
import java.util.Set;

/**
 * 认证后的控制台用户：身份提供方中的用户标识（默认取 OIDC 的 {@code sub}，可配置为其他声明）与令牌中的组。租户内的角色不来自令牌，
 * 由 control-plane 按用户标识查角色绑定（ADR-0042）；组只用于按组判定平台管理员（ADR-0043）。
 *
 * @param subject 用户标识；必填，在身份提供方内唯一且不变
 * @param groups 令牌中的组；可为空集合
 */
public record ConsoleUser(String subject, Set<String> groups) {

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
    }

    /**
     * 创建不带组的用户。
     *
     * @param subject 用户标识
     */
    public ConsoleUser(String subject) {
        this(subject, Set.of());
    }
}
