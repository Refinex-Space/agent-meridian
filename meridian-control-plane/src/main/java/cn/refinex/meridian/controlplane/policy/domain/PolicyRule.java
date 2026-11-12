package cn.refinex.meridian.controlplane.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 租户或项目策略中的一条权限规则（架构设计「权限判定」）。语义与 engine-spi 的 {@code PermissionRule} 相同：规则内容由工具解释，为空表示
 * 整工具规则；所属层由 {@code projectId} 是否为空决定。
 *
 * @param id 规则 ID（{@code prl_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目；为空表示租户策略
 * @param toolName 工具名
 * @param ruleContent 规则内容；可为空
 * @param behavior 结果：{@code allow}、{@code deny}、{@code ask}
 * @param source 来源：{@value #POLICY} 或 {@value #REMEMBERED}
 * @param sourceActionId 记住的决定来源的待处理动作 ID；管理员添加的为空
 * @param createdBy 添加者或做出决定的主体
 * @param createdAt 添加时间
 */
public record PolicyRule(
        String id,
        String tenantId,
        String projectId,
        String toolName,
        String ruleContent,
        String behavior,
        String source,
        String sourceActionId,
        String createdBy,
        Instant createdAt) {

    /** 来源：管理员添加。 */
    public static final String POLICY = "policy";

    /** 来源：审批时"记住此决定"（项目级）写入。 */
    public static final String REMEMBERED = "remembered";

    /** 结果的取值。 */
    public static final List<String> BEHAVIORS = List.of("allow", "deny", "ask");

    /**
     * 创建规则。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 结果或来源的取值未知时
     */
    public PolicyRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(source, "source");
        if (!BEHAVIORS.contains(behavior)) {
            throw new IllegalArgumentException("未知的规则结果：" + behavior);
        }
        if (!POLICY.equals(source) && !REMEMBERED.equals(source)) {
            throw new IllegalArgumentException("未知的规则来源：" + source);
        }
    }
}
