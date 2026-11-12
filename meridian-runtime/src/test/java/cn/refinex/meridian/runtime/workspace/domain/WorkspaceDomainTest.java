package cn.refinex.meridian.runtime.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.runtime.approval.domain.PlatformSafetyRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 工作区 API 的路径规则（ADR-0050）。 */
class WorkspaceDomainTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "/a", "a/", "../a", "a/../b", "a/./b", "a//b", ".", "..", "a\\b", "a\u0000b"})
    void irregularPathsAreRejected(String path) {
        assertThatThrownBy(() -> WorkspaceApiPaths.requireValid(path))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(WorkspaceErrorCode.INVALID_PATH));
    }

    @Test
    void regularPathsArePassedThrough() {
        assertThat(WorkspaceApiPaths.requireValid("reports/2026/q3.md")).isEqualTo("reports/2026/q3.md");
        assertThat(WorkspaceApiPaths.requireValid(".env")).isEqualTo(".env");
        assertThat(WorkspaceApiPaths.requireValidPrefix("reports/")).isEqualTo("reports/");
        assertThat(WorkspaceApiPaths.requireValidPrefix(null)).isEmpty();
        assertThatThrownBy(() -> WorkspaceApiPaths.requireValidPrefix("../")).isInstanceOf(MeridianException.class);
    }

    @Test
    void protectedPathsMatchThePlatformSafetyRules() {
        assertThat(WorkspaceApiPaths.PROTECTED)
                .as("与平台安全规则的写入保护一致（dir/** 写成 dir/）")
                .containsExactlyElementsOf(PlatformSafetyRules.PROTECTED_WRITE_PATHS.stream()
                        .map(p -> p.endsWith("/**") ? p.substring(0, p.length() - 2) : p)
                        .toList());
        assertThat(WorkspaceApiPaths.isProtected("AGENTS.md")).isTrue();
        assertThat(WorkspaceApiPaths.isProtected("skills")).isTrue();
        assertThat(WorkspaceApiPaths.isProtected("skills/docx/a.py")).isTrue();
        assertThat(WorkspaceApiPaths.isProtected("subagents/r.md")).isTrue();
        assertThat(WorkspaceApiPaths.isProtected("docs/AGENTS.md")).isFalse();
        assertThat(WorkspaceApiPaths.isProtected("skills-notes.md")).isFalse();
    }

    @Test
    void errorCodesAreRegistered() {
        ErrorCodes.verify(WorkspaceErrorCode.values());
    }
}
