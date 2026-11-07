package cn.refinex.meridian.engine.spi.spec;

import java.util.List;
import java.util.Objects;

/**
 * 平台的权限策略：一个模式加一组规则，见架构设计「权限判定」。
 *
 * <p>它既表示某一层的策略（例如智能体版本自带的策略），也表示五层合成后的结果；合成后的结果在每轮开始前
 * 翻译为 AgentScope 的 {@code PermissionContextState}，经 {@code replacePermissionContext} 安装。
 *
 * @param mode 权限模式；必填
 * @param rules 规则，按声明顺序；可为空列表
 */
public record PermissionPolicy(PermissionMode mode, List<PermissionRule> rules) {

    /**
     * 创建策略，对规则列表做不可变复制。
     *
     * @throws NullPointerException 缺少模式，或规则列表含空元素时
     */
    public PermissionPolicy {
        Objects.requireNonNull(mode, "mode");
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * 默认模式、没有任何规则的策略。
     *
     * @return 空策略
     */
    public static PermissionPolicy empty() {
        return new PermissionPolicy(PermissionMode.DEFAULT, List.of());
    }
}
