package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每轮开始前的权限合成：把五层规则合成为平台的 {@link PermissionPolicy}，由内核翻译为 AgentScope 的
 * {@code PermissionContextState} 安装（架构设计「权限判定」「子智能体」第 4 条）。纯逻辑，不访问数据库。
 *
 * <p>合成规则：
 * <ol>
 *   <li>规则是五层的并集，按层从高到低排列：平台安全规则（{@link PlatformSafetyRules}）、租户、项目、智能体版本、会话。每条规则
 *       带上所属层；同一工具、同一内容、同一结果的规则只保留最高层的一条。
 *   <li>冲突按 AgentScope {@code PermissionEngine} 的原生顺序裁决：deny → ask → 工具自检 → allow → BYPASS → 默认 ask。所以高层的
 *       deny 与 ask 不会被低层的 allow 放开，低层只能收紧；"记住此决定"写入的 allow 只对没有命中 deny、ask 规则的调用生效。
 *   <li>模式取智能体配置；会话只能收紧：{@code unattended} 会话固定为 {@code DONT_ASK}。
 *   <li>没有人能审批的执行（{@code unattended} 会话、所有子线程）：使用 {@code DONT_ASK}，并把全部 ask 规则改写为 deny。原因是
 *       {@code DONT_ASK} 只把最后一步的默认 ask 变成 deny，显式 ask 规则在第 2 步就直接返回 ask（2.0.4 已核实）。
 *   <li>{@code unattended} 会话额外 deny {@code plan_enter}：退出计划模式需要人工确认（{@code plan_exit} 的工具自检恒为 ask）。
 * </ol>
 *
 * <p>子智能体：在主线程的合成结果上为每个子智能体预先合成，放进 {@code TurnContext.subagentPolicies} 供 {@code spawn_subagent}
 * （T13）使用。子线程同样是 {@code DONT_ASK} 加 ask 改写为 deny；可用的 allow 规则是父智能体 allow 规则的子集：继承父智能体中
 * 工具属于子智能体工具集的 allow，子智能体配置自带的 allow 只在被父智能体的 allow 覆盖（同一工具，父规则不带内容或内容相同）时
 * 保留；父智能体与子智能体配置中的 deny 全部生效。
 *
 * <p>工具自检返回的安全类 ask（例如危险路径）不受规则改写影响，兜底见 {@code approval} 的自动拒绝。线程安全：无状态。
 */
public final class PermissionSynthesizer {

    /** 计划模式的入口工具名，与 AgentScope 2.0.4 {@code PlanModeTools.PLAN_ENTER} 一致。 */
    public static final String PLAN_ENTER = "plan_enter";

    private PermissionSynthesizer() {}

    /**
     * 合成主线程的权限策略。
     *
     * @param layers 合成输入
     * @return 合成后的策略
     */
    public static PermissionPolicy mainThread(PermissionLayers layers) {
        Objects.requireNonNull(layers, "layers");
        List<PermissionRule> merged = merge(layers);
        if (layers.interaction() == Interaction.ATTENDED) {
            return new PermissionPolicy(layers.agentVersion().mode(), merged);
        }
        List<PermissionRule> rules = new ArrayList<>(denyAsks(merged));
        rules.add(new PermissionRule(PLAN_ENTER, null, PermissionBehavior.DENY, PolicyLayer.PLATFORM));
        return new PermissionPolicy(PermissionMode.DONT_ASK, distinct(rules));
    }

    /**
     * 为智能体配置的每个子智能体预先合成子线程的权限策略。
     *
     * @param layers 主线程的合成输入
     * @param subagents 子智能体配置
     * @return 子智能体名 → 策略，顺序与配置相同
     */
    public static Map<String, PermissionPolicy> subagents(PermissionLayers layers, List<SubagentSpec> subagents) {
        Objects.requireNonNull(layers, "layers");
        Map<String, PermissionPolicy> policies = new LinkedHashMap<>();
        if (subagents.isEmpty()) {
            return policies;
        }
        List<PermissionRule> parent = merge(layers);
        for (SubagentSpec subagent : subagents) {
            policies.put(subagent.name(), subagent(parent, subagent));
        }
        return policies;
    }

    /**
     * 子线程的权限改写：{@code DONT_ASK}，ask 改写为 deny，allow 是父智能体 allow 的子集（见类注释）。
     *
     * @param parent 主线程合并后的规则（改写之前）
     * @param subagent 子智能体配置
     * @return 子线程的策略
     */
    static PermissionPolicy subagent(List<PermissionRule> parent, SubagentSpec subagent) {
        List<PermissionRule> own = stamp(subagent.permissions().rules(), PolicyLayer.AGENT_VERSION);
        List<PermissionRule> parentAllows = parent.stream()
                .filter(rule -> rule.behavior() == PermissionBehavior.ALLOW)
                .toList();
        List<PermissionRule> rules = new ArrayList<>(denyAsks(withoutAllows(parent)));
        rules.addAll(denyAsks(withoutAllows(own)));
        for (PermissionRule allow : parentAllows) {
            if (subagent.tools().contains(allow.toolName())) {
                rules.add(allow);
            }
        }
        for (PermissionRule rule : own) {
            if (rule.behavior() == PermissionBehavior.ALLOW
                    && subagent.tools().contains(rule.toolName())
                    && coveredBy(rule, parentAllows)) {
                rules.add(rule);
            }
        }
        return new PermissionPolicy(PermissionMode.DONT_ASK, distinct(rules));
    }

    /** 五层规则按层从高到低合并，每条规则带上所属层。 */
    static List<PermissionRule> merge(PermissionLayers layers) {
        List<PermissionRule> rules = new ArrayList<>(PlatformSafetyRules.RULES);
        rules.addAll(stamp(layers.tenant(), PolicyLayer.TENANT));
        rules.addAll(stamp(layers.project(), PolicyLayer.PROJECT));
        rules.addAll(stamp(layers.agentVersion().rules(), PolicyLayer.AGENT_VERSION));
        rules.addAll(stamp(layers.session(), PolicyLayer.SESSION));
        return distinct(rules);
    }

    private static List<PermissionRule> stamp(List<PermissionRule> rules, PolicyLayer layer) {
        return rules.stream()
                .map(rule -> rule.layer() == layer
                        ? rule
                        : new PermissionRule(rule.toolName(), rule.ruleContent(), rule.behavior(), layer))
                .toList();
    }

    /** 把 ask 规则改写为同一层的 deny。 */
    private static List<PermissionRule> denyAsks(List<PermissionRule> rules) {
        return rules.stream()
                .map(rule -> rule.behavior() == PermissionBehavior.ASK
                        ? new PermissionRule(rule.toolName(), rule.ruleContent(), PermissionBehavior.DENY, rule.layer())
                        : rule)
                .toList();
    }

    private static List<PermissionRule> withoutAllows(List<PermissionRule> rules) {
        return rules.stream()
                .filter(rule -> rule.behavior() != PermissionBehavior.ALLOW)
                .toList();
    }

    /** 子智能体自带的 allow 被父智能体的 allow 覆盖：同一工具，父规则是整工具规则或内容相同。 */
    private static boolean coveredBy(PermissionRule rule, List<PermissionRule> parentAllows) {
        return parentAllows.stream()
                .anyMatch(allow -> allow.toolName().equals(rule.toolName())
                        && (allow.ruleContent() == null || allow.ruleContent().equals(rule.ruleContent())));
    }

    /** 同一工具、同一内容、同一结果的规则只保留第一条（层最高的一条）。 */
    private static List<PermissionRule> distinct(List<PermissionRule> rules) {
        Map<RuleKey, PermissionRule> unique = new LinkedHashMap<>();
        for (PermissionRule rule : rules) {
            unique.putIfAbsent(new RuleKey(rule.toolName(), rule.ruleContent(), rule.behavior()), rule);
        }
        return List.copyOf(unique.values());
    }

    private record RuleKey(String toolName, String ruleContent, PermissionBehavior behavior) {}
}
