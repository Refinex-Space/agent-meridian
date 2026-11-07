package cn.refinex.meridian.engine.spi.spec;

import java.util.Objects;

/**
 * 平台的一条权限规则，可以无损翻译为 AgentScope 的 {@code PermissionRule}，见架构设计「权限判定」「平台工具」。
 *
 * @param toolName 工具名，例如 {@code bash}、{@code mcp__crm__query}；必填
 * @param ruleContent 规则内容，语法由工具的 {@code matchRule} 解释，例如 {@code git status:*}、{@code reports/**}；
 *     可为空，表示整工具规则
 * @param behavior 命中后的结果；必填
 * @param layer 规则所属的层；必填
 */
public record PermissionRule(String toolName, String ruleContent, PermissionBehavior behavior, PolicyLayer layer) {

    /**
     * 创建规则。
     *
     * @throws NullPointerException 缺少工具名、结果或所属层时
     * @throws IllegalArgumentException 工具名为空白时
     */
    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(layer, "layer");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("权限规则的工具名不能为空白");
        }
    }
}
