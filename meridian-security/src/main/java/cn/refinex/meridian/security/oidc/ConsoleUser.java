package cn.refinex.meridian.security.oidc;

import java.util.Objects;

/**
 * 认证后的控制台用户：身份提供方中的主体标识（OIDC 的 {@code sub}）。角色不来自令牌，由 control-plane 按主体标识查角色绑定
 * （ADR-0042）。
 *
 * @param subject 主体标识；必填，在身份提供方内唯一且不变
 */
public record ConsoleUser(String subject) {

    /**
     * 创建用户。
     *
     * @throws IllegalArgumentException 主体标识为空时
     */
    public ConsoleUser {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("控制台用户的主体标识不能为空");
        }
    }
}
