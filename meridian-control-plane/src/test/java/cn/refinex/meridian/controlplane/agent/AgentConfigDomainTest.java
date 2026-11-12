package cn.refinex.meridian.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.agent.domain.Agent;
import cn.refinex.meridian.controlplane.agent.domain.AgentErrorCode;
import cn.refinex.meridian.controlplane.agent.domain.AgentSpecRules;
import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
import cn.refinex.meridian.controlplane.agent.domain.SpecChange;
import cn.refinex.meridian.controlplane.agent.domain.SpecDiff;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.controlplane.environment.domain.Environment;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentConfig;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentErrorCode;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.mcp.domain.McpErrorCode;
import cn.refinex.meridian.controlplane.mcp.domain.McpServer;
import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import cn.refinex.meridian.controlplane.policy.domain.PolicyErrorCode;
import cn.refinex.meridian.controlplane.policy.domain.PolicyRule;
import cn.refinex.meridian.controlplane.skill.domain.Skill;
import cn.refinex.meridian.controlplane.skill.domain.SkillErrorCode;
import cn.refinex.meridian.controlplane.skill.domain.SkillFile;
import cn.refinex.meridian.controlplane.skill.domain.SkillPackage;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 智能体配置的值对象与纯规则（T15b，ADR-0047）：规格中 Schema 表达不了的规则、版本差异、引用、环境配置的校验与规范化、技能包的校验、MCP
 * 工具的副作用等级、策略规则的取值、错误码。
 */
class AgentConfigDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void specRulesMatchTheResolvedAgentSpecConstructorChecks() {
        JsonNode valid = json("""
                {"tools":["read_file","spawn_subagent"],
                 "mcp_servers":[{"mcp_server_id":"mcp_1","name":"crm","enabled_tools":["lookup_order"]}],
                 "skills":[{"skill_id":"skl_1","version":1,"name":"docx"}],
                 "subagents":[{"name":"reader","tools":["read_file","mcp__crm__lookup_order"],"timeout":"PT10M",
                               "skills":[]}],
                 "knowledge":{"knowledge_base_ids":["kb_1"],"prefetch":true}}""");
        assertThat(AgentSpecRules.check(valid)).isEmpty();

        JsonNode invalid = json("""
                {"tools":["read_file"],
                 "mcp_servers":[{"mcp_server_id":"mcp_1","name":"crm","enabled_tools":["lookup_order"]},
                                {"mcp_server_id":"mcp_1","name":"crm","enabled_tools":[]}],
                 "skills":[{"skill_id":"skl_1","version":1,"name":"docx"},{"skill_id":"skl_2","version":1,"name":"docx"}],
                 "subagents":[{"name":"a","tools":["web_fetch","mcp__crm__create_ticket","mcp__other__x","mcp__bad"],
                               "timeout":"PT11M","skills":[]},
                              {"name":"a","tools":[],"timeout":"later","skills":[]}],
                 "knowledge":{"knowledge_base_ids":[],"prefetch":true}}""");
        assertThat(AgentSpecRules.check(invalid))
                .extracting(FieldViolation::field)
                .containsExactlyInAnyOrder(
                        "spec.mcp_servers.1.mcp_server_id",
                        "spec.mcp_servers.1.name",
                        "spec.subagents.1.name",
                        "spec.skills.1.name",
                        "spec.tools",
                        "spec.subagents.0.tools.0",
                        "spec.subagents.0.tools.1",
                        "spec.subagents.0.tools.2",
                        "spec.subagents.0.tools.3",
                        "spec.subagents.0.timeout",
                        "spec.subagents.1.timeout",
                        "spec.knowledge.knowledge_base_ids");
    }

    @Test
    void enabledToolsRestrictMcpGrantsToSubagentsAndAnEmptyListGrantsAll() {
        JsonNode restricted = json("""
                {"tools":["spawn_subagent"],
                 "mcp_servers":[{"name":"crm","enabled_tools":["lookup_order"]}],
                 "subagents":[{"name":"a","tools":["mcp__crm__create_ticket"],"timeout":"PT1M"}]}""");
        JsonNode all = json("""
                {"tools":["spawn_subagent"],
                 "mcp_servers":[{"name":"crm","enabled_tools":[]}],
                 "subagents":[{"name":"a","tools":["mcp__crm__create_ticket"],"timeout":"PT1M"}]}""");

        assertThat(AgentSpecRules.check(restricted))
                .extracting(FieldViolation::field)
                .containsExactly("spec.subagents.0.tools.0");
        assertThat(AgentSpecRules.check(all)).isEmpty();
    }

    @Test
    void diffWalksObjectsAndArraysByPositionAndEscapesPointers() {
        JsonNode base = json("""
                {"a":1,"same":{"x":[1,2]},"list":[1,2,3],"gone":true,"a/b~c":"old"}""");
        JsonNode target = json("""
                {"a":2,"same":{"x":[1,2]},"list":[1,9],"new":null,"a/b~c":"new"}""");

        List<String> changes = SpecDiff.compare(base, target).stream()
                .map(c -> c.kind().value() + " " + c.path())
                .toList();

        assertThat(changes)
                .containsExactly(
                        "changed /a",
                        "changed /a~1b~0c",
                        "removed /gone",
                        "changed /list/1",
                        "removed /list/2",
                        "added /new");
        assertThat(SpecDiff.compare(base, base)).isEmpty();
        assertThat(SpecDiff.compare(json("1"), json("2")))
                .singleElement()
                .satisfies(c -> assertThat(c.path()).isEqualTo("/"));
        assertThat(SpecDiff.compare(json("[]"), json("[{\"k\":1}]")))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.kind()).isEqualTo(SpecChange.Kind.ADDED);
                    assertThat(c.before()).isNull();
                });
    }

    @Test
    void referencesCoverModelsSkillsMcpServersTheirSecretsAndKnowledgeBasesOfSubagentsToo() {
        JsonNode spec = json("""
                {"model":{"model_id":"mdl_a","fallback_model_id":"mdl_b"},
                 "skills":[{"skill_id":"skl_a","version":1}],
                 "mcp_servers":[{"mcp_server_id":"mcp_a","header_secret_ids":{"Authorization":"sec_a"}}],
                 "knowledge":{"knowledge_base_ids":["kb_a"]},
                 "subagents":[{"model":{"model_id":"mdl_c","fallback_model_id":null},"skills":[{"skill_id":"skl_a"}]}]}""");

        assertThat(SpecReference.of(spec))
                .extracting(r -> r.type().value() + ":" + r.id())
                .containsExactly(
                        "model:mdl_a",
                        "model:mdl_b",
                        "skill:skl_a",
                        "mcp_server:mcp_a",
                        "secret:sec_a",
                        "knowledge_base:kb_a",
                        "model:mdl_c");
    }

    @Test
    void environmentConfigNormalizesTheAllowlistAndRejectsInvalidCombinations() {
        EnvironmentConfig config =
                new EnvironmentConfig("sandbox", "small", List.of(" PyPI.org ", "*.example.com", "pypi.org", "echo"));
        assertThat(config.egressAllowlist()).containsExactly("pypi.org", "*.example.com", "echo");
        assertThat(new EnvironmentConfig("none", null, null).egressAllowlist()).isEmpty();

        assertThatThrownBy(() -> new EnvironmentConfig("sandbox", null, List.of("10.0.0.1", "a.com:443", "x/y", "*")))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .extracting(FieldViolation::field)
                                .containsExactly(
                                        "size",
                                        "egress_allowlist.0",
                                        "egress_allowlist.1",
                                        "egress_allowlist.2",
                                        "egress_allowlist.3"));
        assertThatThrownBy(() -> new EnvironmentConfig("gpu", "huge", List.of()))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .extracting(FieldViolation::field)
                                .containsExactly("tier", "size"));
        assertThatThrownBy(() -> new EnvironmentConfig("none", "small", List.of()))
                .isInstanceOf(ValidationException.class);
        List<String> tooMany = Stream.iterate(0, i -> i + 1)
                .limit(101)
                .map(i -> "h" + i + ".com")
                .toList();
        assertThatThrownBy(() -> new EnvironmentConfig("sandbox", "small", tooMany))
                .isInstanceOf(ValidationException.class);

        Environment environment = new Environment("env_1", "ten_1", "prj_1", "e", 2, config, "u", NOW, NOW);
        EnvironmentRevision revision = new EnvironmentRevision("env_1", 2, "ten_1", "prj_1", config, "u", NOW);
        assertThat(environment.config()).isEqualTo(revision.config());
    }

    @Test
    void skillPackagesNeedFrontmatterWithNameAndDescriptionAndSafeRelativePaths() {
        String skillMd = "---\nname: docx\ndescription: Turn Markdown into Word.\n---\n# Body\n";
        SkillPackage parsed =
                SkillPackage.parse(List.of(Map.entry("scripts/run.py", "print(1)"), Map.entry("SKILL.md", skillMd)));
        assertThat(parsed.name()).isEqualTo("docx");
        assertThat(parsed.description()).isEqualTo("Turn Markdown into Word.");
        assertThat(parsed.files().firstKey()).isEqualTo("SKILL.md");
        assertThat(SkillPackage.parse(List.of(Map.entry("SKILL.md", "﻿" + skillMd)))
                        .name())
                .isEqualTo("docx");

        assertViolations(List.of(Map.entry("README.md", "x")), "files");
        assertViolations(List.of(Map.entry("SKILL.md", "# no frontmatter")), "files");
        assertViolations(List.of(Map.entry("SKILL.md", "---\n[unclosed\n---\n")), "files");
        assertViolations(List.of(Map.entry("SKILL.md", "---\n- a list\n---\n")), "files");
        assertViolations(List.of(Map.entry("SKILL.md", "---\nname: Bad Name\ndescription: d\n---\n")), "files");
        assertViolations(List.of(Map.entry("SKILL.md", "---\nname: ok\n---\n")), "files");
        assertViolations(
                List.of(
                        Map.entry("SKILL.md", skillMd),
                        Map.entry("../x", "y"),
                        Map.entry("/abs", "y"),
                        Map.entry("a\\b", "y")),
                "files.1.path",
                "files.2.path",
                "files.3.path");
        assertViolations(List.of(Map.entry("SKILL.md", skillMd), Map.entry("SKILL.md", skillMd)), "files.1.path");
        assertViolations(
                List.of(
                        Map.entry("SKILL.md", skillMd),
                        Map.entry("big.txt", "x".repeat(SkillPackage.MAX_FILE_BYTES + 1))),
                "files.1.content");

        SkillVersion version = new SkillVersion(
                "skl_1",
                1,
                "ten_1",
                "prj_1",
                "docx",
                "d",
                List.of(new SkillFile("SKILL.md", 10, "ab")),
                SkillVersion.objectRoot("ten_1", "prj_1", "skl_1", 1),
                "u",
                NOW);
        assertThat(version.objectRoot()).isEqualTo("cp/skills/ten_1/prj_1/skl_1/v1/");
        assertThat(new Skill("skl_1", "ten_1", "prj_1", "docx", "d", 1, "u", NOW, NOW).latestVersion())
                .isEqualTo(1);
    }

    @Test
    void mcpToolSideEffectsFallBackToTheServerLevelThenNonIdempotent() {
        McpServer annotated = mcp("idempotent", Map.of("lookup_order", "none"));
        McpServer plain = mcp(null, null);

        assertThat(annotated.sideEffectOf("lookup_order")).isEqualTo("none");
        assertThat(annotated.sideEffectOf("create_ticket")).isEqualTo("idempotent");
        assertThat(plain.sideEffectOf("create_ticket")).isEqualTo(McpServer.DEFAULT_SIDE_EFFECT);
        assertThat(plain.headerSecretIds()).isEmpty();
        assertThat(new McpTool("t", null).description()).isEmpty();
    }

    @Test
    void policyRulesAndVersionsValidateTheirEnumeratedValues() {
        PolicyRule rule =
                new PolicyRule("prl_1", "ten_1", null, "bash", null, "deny", PolicyRule.POLICY, null, "u", NOW);
        assertThat(rule.projectId()).isNull();
        assertThatThrownBy(() -> new PolicyRule(
                        "prl_1", "ten_1", null, "bash", null, "maybe", PolicyRule.POLICY, null, "u", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRule("prl_1", "ten_1", null, "bash", null, "allow", "other", null, "u", NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AgentVersion.Status.of("published")).isEqualTo(AgentVersion.Status.PUBLISHED);
        assertThatThrownBy(() -> AgentVersion.Status.of("archived")).isInstanceOf(IllegalArgumentException.class);
        AgentVersion draft = new AgentVersion(
                "agv_1", "ten_1", "prj_1", "agt_1", 1, AgentVersion.Status.DRAFT, "{}", "u", NOW, NOW, null, null);
        assertThat(draft.published()).isFalse();
        assertThat(new Agent("agt_1", "ten_1", "prj_1", "a", null, "u", NOW, NOW, null).description())
                .isEmpty();
    }

    @Test
    void errorCodesUseTheirDomainPrefixesAndHttpStatuses() {
        List<ErrorCode> codes = Stream.of(
                        AgentErrorCode.values(),
                        EnvironmentErrorCode.values(),
                        SkillErrorCode.values(),
                        McpErrorCode.values(),
                        PolicyErrorCode.values())
                .flatMap(Stream::of)
                .map(ErrorCode.class::cast)
                .toList();
        assertThat(codes).allSatisfy(code -> {
            assertThat(code.code()).matches("^(agent|environment|skill|mcp_server|permission_rule)\\.[a-z_]+$");
            assertThat(code.status()).isIn(400, 404, 409);
            assertThat(code.title()).isNotBlank();
        });
        assertThat(McpErrorCode.PROBE_FAILED.status()).isEqualTo(400);
        assertThat(AgentErrorCode.VERSION_PUBLISHED.status()).isEqualTo(409);
    }

    private static void assertViolations(List<Map.Entry<String, String>> files, String... fields) {
        assertThatThrownBy(() -> SkillPackage.parse(files))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .extracting(FieldViolation::field)
                                .contains(fields));
    }

    private static McpServer mcp(String sideEffect, Map<String, String> toolSideEffects) {
        return new McpServer(
                "mcp_1",
                "ten_1",
                "prj_1",
                "crm",
                "sse",
                "http://127.0.0.1/sse",
                null,
                sideEffect,
                toolSideEffects,
                null,
                NOW,
                "u",
                NOW,
                NOW);
    }

    private static JsonNode json(String text) {
        return JsonMappers.lenient().readTree(text);
    }
}
