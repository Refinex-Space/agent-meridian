package cn.refinex.meridian.runtime.approval.domain;

import static cn.refinex.meridian.engine.spi.spec.PermissionBehavior.ALLOW;
import static cn.refinex.meridian.engine.spi.spec.PermissionBehavior.ASK;
import static cn.refinex.meridian.engine.spi.spec.PermissionBehavior.DENY;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 权限合成（架构设计「权限判定」「子智能体」第 4 条）：五层优先级冲突、模式收紧、unattended 与子线程两种改写。判定本身由
 * AgentScope 的 {@code PermissionEngine} 完成，这里断言合成结果；真实判定见 runtime 的权限集成测试与 P15。
 */
class PermissionSynthesizerTest {

    @Test
    void platformSafetyRulesComeFirstAndLowerLayersCannotLoosenThem() {
        PermissionLayers layers = attended(
                PermissionMode.BYPASS,
                List.of(rule("write_file", null, ALLOW)),
                List.of(rule("write_file", "AGENTS.md", ALLOW)),
                List.of(rule("read_file", "**/.env", ALLOW)));

        PermissionPolicy policy = PermissionSynthesizer.mainThread(layers);

        assertThat(policy.rules().subList(0, PlatformSafetyRules.RULES.size())).isEqualTo(PlatformSafetyRules.RULES);
        assertThat(policy.rules())
                .contains(new PermissionRule("write_file", "AGENTS.md", DENY, PolicyLayer.PLATFORM))
                .contains(new PermissionRule("write_file", "skills/**", DENY, PolicyLayer.PLATFORM))
                .contains(new PermissionRule("edit_file", "MEMORY.md", DENY, PolicyLayer.PLATFORM))
                .contains(new PermissionRule("read_file", "**/.env", DENY, PolicyLayer.PLATFORM))
                .contains(new PermissionRule("grep_files", "**/*.pem", DENY, PolicyLayer.PLATFORM));
        // 低层的 allow 仍在，但 PermissionEngine 先判 deny：平台的 deny 总是生效
        assertThat(policy.rules()).contains(new PermissionRule("write_file", "AGENTS.md", ALLOW, PolicyLayer.PROJECT));
    }

    @Test
    void rulesFromFiveLayersKeepLayerOrderAndSource() {
        PermissionLayers layers = new PermissionLayers(
                List.of(rule("bash", null, DENY)),
                List.of(rule("bash", "git status:*", ALLOW)),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of(rule("web_fetch", null, ASK))),
                List.of(rule("web_fetch", "domain:docs.example.com", ALLOW)),
                Interaction.ATTENDED);

        List<PermissionRule> rules = PermissionSynthesizer.mainThread(layers).rules();
        List<PermissionRule> below = rules.subList(PlatformSafetyRules.RULES.size(), rules.size());

        assertThat(below)
                .containsExactly(
                        new PermissionRule("bash", null, DENY, PolicyLayer.TENANT),
                        new PermissionRule("bash", "git status:*", ALLOW, PolicyLayer.PROJECT),
                        new PermissionRule("web_fetch", null, ASK, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("web_fetch", "domain:docs.example.com", ALLOW, PolicyLayer.SESSION));
    }

    @Test
    void identicalRulesFromSeveralLayersKeepTheHighestLayer() {
        PermissionLayers layers = new PermissionLayers(
                List.of(rule("web_search", null, ALLOW)),
                List.of(rule("web_search", null, ALLOW), rule("web_search", null, DENY)),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of(rule("web_search", null, ALLOW))),
                List.of(rule("web_search", null, ALLOW)),
                Interaction.ATTENDED);

        List<PermissionRule> webSearch = PermissionSynthesizer.mainThread(layers).rules().stream()
                .filter(r -> r.toolName().equals("web_search"))
                .toList();

        assertThat(webSearch)
                .containsExactly(
                        new PermissionRule("web_search", null, ALLOW, PolicyLayer.TENANT),
                        new PermissionRule("web_search", null, DENY, PolicyLayer.PROJECT));
    }

    @Test
    void attendedSessionTakesTheAgentModeAndKeepsAskRules() {
        PermissionLayers layers = attended(
                PermissionMode.ACCEPT_EDITS, List.of(), List.of(), List.of(rule("write_file", "reports/**", ASK)));

        PermissionPolicy policy = PermissionSynthesizer.mainThread(layers);

        assertThat(policy.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(policy.rules())
                .contains(new PermissionRule("write_file", "reports/**", ASK, PolicyLayer.AGENT_VERSION));
        assertThat(policy.rules()).noneMatch(r -> r.toolName().equals(PermissionSynthesizer.PLAN_ENTER));
    }

    @Test
    void unattendedSessionTightensEveryModeToDontAsk() {
        for (PermissionMode mode : PermissionMode.values()) {
            PermissionLayers layers = new PermissionLayers(
                    List.of(), List.of(), new PermissionPolicy(mode, List.of()), List.of(), Interaction.UNATTENDED);

            assertThat(PermissionSynthesizer.mainThread(layers).mode())
                    .as(mode.name())
                    .isEqualTo(PermissionMode.DONT_ASK);
        }
    }

    @Test
    void unattendedSessionRewritesEveryAskToDenyAndDeniesPlanEnter() {
        PermissionLayers layers = new PermissionLayers(
                List.of(rule("bash", null, ASK)),
                List.of(rule("web_fetch", "domain:a.example.com", ASK)),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of(rule("write_file", "reports/**", ASK))),
                List.of(rule("read_file", null, ALLOW)),
                Interaction.UNATTENDED);

        PermissionPolicy policy = PermissionSynthesizer.mainThread(layers);

        assertThat(policy.mode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(policy.rules()).noneMatch(r -> r.behavior() == ASK);
        assertThat(policy.rules())
                .contains(
                        new PermissionRule("bash", null, DENY, PolicyLayer.TENANT),
                        new PermissionRule("web_fetch", "domain:a.example.com", DENY, PolicyLayer.PROJECT),
                        new PermissionRule("write_file", "reports/**", DENY, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("read_file", null, ALLOW, PolicyLayer.SESSION),
                        new PermissionRule(PermissionSynthesizer.PLAN_ENTER, null, DENY, PolicyLayer.PLATFORM));
    }

    @Test
    void subagentPoliciesUseDontAskDenyAsksAndOnlyInheritParentAllows() {
        PermissionLayers layers = new PermissionLayers(
                List.of(rule("bash", null, ASK)),
                List.of(rule("read_file", "reports/**", ALLOW)),
                new PermissionPolicy(
                        PermissionMode.BYPASS,
                        List.of(
                                rule("web_fetch", null, ALLOW),
                                rule("bash", null, ALLOW),
                                rule("grep_files", null, ASK))),
                List.of(rule("knowledge_search", null, ALLOW)),
                Interaction.ATTENDED);
        SubagentSpec researcher = subagent(
                "researcher",
                Set.of("read_file", "web_fetch", "grep_files"),
                List.of(
                        rule("web_fetch", "domain:docs.example.com", ALLOW),
                        rule("read_file", "secrets/**", ALLOW),
                        rule("read_file", "drafts/**", ASK),
                        rule("web_fetch", "domain:evil.example.com", DENY)));

        PermissionPolicy policy =
                PermissionSynthesizer.subagents(layers, List.of(researcher)).get("researcher");

        assertThat(policy.mode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(policy.rules()).noneMatch(r -> r.behavior() == ASK);
        assertThat(allows(policy))
                .containsExactlyInAnyOrder(
                        new PermissionRule("read_file", "reports/**", ALLOW, PolicyLayer.PROJECT),
                        new PermissionRule("web_fetch", null, ALLOW, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("web_fetch", "domain:docs.example.com", ALLOW, PolicyLayer.AGENT_VERSION));
        assertThat(policy.rules())
                .contains(
                        new PermissionRule("bash", null, DENY, PolicyLayer.TENANT),
                        new PermissionRule("grep_files", null, DENY, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("read_file", "drafts/**", DENY, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("web_fetch", "domain:evil.example.com", DENY, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("read_file", "**/.env", DENY, PolicyLayer.PLATFORM));
    }

    @Test
    void unattendedSessionGivesTheSameSubagentRewrite() {
        SubagentSpec writer = subagent("writer", Set.of("write_file"), List.of(rule("write_file", "out/**", ASK)));
        PermissionLayers layers = new PermissionLayers(
                List.of(),
                List.of(),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of(rule("write_file", null, ALLOW))),
                List.of(),
                Interaction.UNATTENDED);

        PermissionPolicy policy =
                PermissionSynthesizer.subagents(layers, List.of(writer)).get("writer");

        assertThat(policy.mode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(policy.rules())
                .contains(
                        new PermissionRule("write_file", "out/**", DENY, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("write_file", null, ALLOW, PolicyLayer.AGENT_VERSION))
                .noneMatch(r -> r.behavior() == ASK);
    }

    @Test
    void customToolsDeclaredByTheSessionAreAllowedAtTheSessionLayerButHigherAsksStillWin() {
        PermissionLayers layers = new PermissionLayers(
                List.of(),
                List.of(rule("notify_customer", null, ASK)),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of()),
                PermissionSynthesizer.customToolRules(List.of("notify_customer", "lookup_order")),
                Interaction.ATTENDED);

        List<PermissionRule> rules = PermissionSynthesizer.mainThread(layers).rules();

        assertThat(rules)
                .contains(
                        new PermissionRule("notify_customer", null, ASK, PolicyLayer.PROJECT),
                        new PermissionRule("notify_customer", null, ALLOW, PolicyLayer.SESSION),
                        new PermissionRule("lookup_order", null, ALLOW, PolicyLayer.SESSION));
        assertThat(rules.indexOf(new PermissionRule("notify_customer", null, ASK, PolicyLayer.PROJECT)))
                .isLessThan(rules.indexOf(new PermissionRule("notify_customer", null, ALLOW, PolicyLayer.SESSION)));
    }

    @Test
    void agentWithoutSubagentsHasNoSubagentPolicies() {
        PermissionLayers layers = attended(PermissionMode.DEFAULT, List.of(), List.of(), List.of());

        Map<String, PermissionPolicy> policies = PermissionSynthesizer.subagents(layers, List.of());

        assertThat(policies).isEmpty();
    }

    private static List<PermissionRule> allows(PermissionPolicy policy) {
        return policy.rules().stream().filter(r -> r.behavior() == ALLOW).toList();
    }

    private static PermissionLayers attended(
            PermissionMode mode,
            List<PermissionRule> tenant,
            List<PermissionRule> project,
            List<PermissionRule> agent) {
        return new PermissionLayers(
                tenant, project, new PermissionPolicy(mode, agent), List.of(), Interaction.ATTENDED);
    }

    private static PermissionRule rule(String tool, String content, PermissionBehavior behavior) {
        // 层由合成器按来源重新标注，这里随意给一个
        return new PermissionRule(tool, content, behavior, PolicyLayer.SESSION);
    }

    private static SubagentSpec subagent(String name, Set<String> tools, List<PermissionRule> rules) {
        return new SubagentSpec(
                name,
                "测试子智能体",
                new ModelSpec("scripted", null, null, null),
                "你是子智能体",
                tools,
                List.of(),
                new PermissionPolicy(PermissionMode.DEFAULT, rules),
                10,
                Duration.ofSeconds(60));
    }
}
