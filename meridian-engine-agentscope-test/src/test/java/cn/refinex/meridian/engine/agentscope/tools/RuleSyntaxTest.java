package cn.refinex.meridian.engine.agentscope.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 路径规范化、路径 glob 与各种规则内容语法（架构设计「平台工具」表，ADR-0031 的平台安全规则依赖路径 glob 的约定）。 */
class RuleSyntaxTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "reports/a.md|reports/a.md",
                "/reports/a.md|reports/a.md",
                "./reports//a.md|reports/a.md",
                "reports\\a.md|reports/a.md",
                "reports/./a.md|reports/a.md",
                "' '|''",
                "/|''",
            })
    void normalizesPathsRelativeToTheWorkspaceRoot(String raw, String expected) {
        assertThat(WorkspacePaths.normalize(raw)).hasValue(expected);
    }

    @Test
    void pathsEscapingTheWorkspaceAreRejected() {
        assertThat(WorkspacePaths.normalize("../etc/passwd")).isEmpty();
        assertThat(WorkspacePaths.normalize("reports/../../x")).isEmpty();
        assertThat(WorkspacePaths.normalize(null)).hasValue("");
    }

    @ParameterizedTest(name = "{0} 匹配 {1}：{2}")
    @CsvSource({
        "reports/**, reports/a.md, true",
        "reports/**, reports/q3/a.md, true",
        "reports/**, reports, true",
        "reports/**, reportsx/a.md, false",
        "**/.env, .env, true",
        "**/.env, config/.env, true",
        "**/.env, config/.env.example, false",
        "**/*.pem, keys/a.pem, true",
        "AGENTS.md, AGENTS.md, true",
        "AGENTS.md, docs/AGENTS.md, false",
        "skills/**, skills/x/SKILL.md, true",
        "/reports/*.md, reports/a.md, true",
        "*.md, docs/a.md, false",
        "**, '', true",
    })
    void pathGlobsFollowThePlatformConvention(String glob, String path, boolean matches) {
        assertThat(WorkspacePaths.matches(glob, path)).isEqualTo(matches);
    }

    @Test
    void pathGlobSyntaxComparesTheNormalizedPathParameter() {
        RuleSyntax syntax = RuleSyntax.pathGlob("path");

        assertThat(syntax.matches("reports/**", Map.of("path", "./reports/a.md")))
                .isTrue();
        assertThat(syntax.matches("reports/**", Map.of("path", "notes/a.md"))).isFalse();
        assertThat(syntax.matches("**", Map.of())).as("缺省路径视为工作区根").isTrue();
        assertThat(syntax.matches("**", Map.of("path", "../x")))
                .as("越出工作区的路径不匹配任何规则")
                .isFalse();
        assertThat(syntax.suggest(Map.of("path", "/reports/a.md"))).hasValue("reports/a.md");
        assertThat(syntax.suggest(Map.of())).isEmpty();
    }

    @Test
    void commandPrefixSyntaxMatchesWholeWords() {
        RuleSyntax syntax = RuleSyntax.commandPrefix("command");

        assertThat(syntax.matches("git status:*", Map.of("command", "git status --short")))
                .isTrue();
        assertThat(syntax.matches("git status:*", Map.of("command", "git status")))
                .isTrue();
        assertThat(syntax.matches("git:*", Map.of("command", "gitk"))).isFalse();
        assertThat(syntax.matches("ls -la", Map.of("command", " ls -la "))).isTrue();
        assertThat(syntax.matches("ls -la", Map.of("command", "ls -la /"))).isFalse();
        assertThat(syntax.suggest(Map.of("command", "npm install left-pad"))).hasValue("npm:*");
    }

    @Test
    void domainSyntaxCoversSubdomains() {
        RuleSyntax syntax = RuleSyntax.domain("url");

        assertThat(syntax.matches("domain:example.com", Map.of("url", "https://docs.example.com/a")))
                .isTrue();
        assertThat(syntax.matches("domain:example.com", Map.of("url", "https://example.com")))
                .isTrue();
        assertThat(syntax.matches("domain:example.com", Map.of("url", "https://badexample.com")))
                .isFalse();
        assertThat(syntax.matches("example.com", Map.of("url", "https://example.com")))
                .isFalse();
        assertThat(syntax.suggest(Map.of("url", "https://Docs.Example.com/x"))).hasValue("domain:docs.example.com");
    }

    @Test
    void resourceIdSyntaxRequiresEveryListedValueToBeCovered() {
        RuleSyntax syntax = RuleSyntax.resourceId("knowledge_base_ids");

        assertThat(syntax.matches("kb_1", Map.of("knowledge_base_ids", "kb_1"))).isTrue();
        assertThat(syntax.matches("kb_1", Map.of("knowledge_base_ids", List.of("kb_1"))))
                .isTrue();
        assertThat(syntax.matches("kb_1", Map.of("knowledge_base_ids", List.of("kb_1", "kb_2"))))
                .isFalse();
        assertThat(RuleSyntax.none().matches("anything", Map.of())).isFalse();
    }
}
