package cn.refinex.meridian.engine.agentscope.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.agentscope.testkit.sandbox.FakeSandbox;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import org.junit.jupiter.api.Test;

/**
 * {@link SandboxWorkspaceFilesystem}：平台路径翻译到 {@code /workspace}、结果翻译回来、{@code glob} 的语义与本次调用的沙箱绑定。
 * 底层是 harness 真实的 {@code SandboxBackedFilesystem}，沙箱是脚本化的替身。
 */
class SandboxWorkspaceFilesystemTest {

    final FakeSandbox sandbox = new FakeSandbox();
    final SandboxWorkspaceFilesystem filesystem = new SandboxWorkspaceFilesystem(new SandboxBackedFilesystem());
    final RuntimeContext bound = RuntimeContext.builder()
            .put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox))
            .build();

    @Test
    void platformPathsMapIntoTheWorkspaceAndBack() {
        assertThat(SandboxWorkspaceFilesystem.toSandbox("/reports/q3.md")).isEqualTo("/workspace/reports/q3.md");
        assertThat(SandboxWorkspaceFilesystem.toSandbox("./reports/")).isEqualTo("/workspace/reports");
        assertThat(SandboxWorkspaceFilesystem.toSandbox("/")).isEqualTo("/workspace");
        assertThat(SandboxWorkspaceFilesystem.toSandbox(null)).isEqualTo("/workspace");
        assertThat(SandboxWorkspaceFilesystem.toPlatform("/workspace/reports/q3.md"))
                .isEqualTo("/reports/q3.md");
        assertThat(SandboxWorkspaceFilesystem.toPlatform("/workspace")).isEqualTo("/");
        assertThat(SandboxWorkspaceFilesystem.toPlatform("./a.md")).isEqualTo("/a.md");
        assertThat(SandboxWorkspaceFilesystem.toPlatform("/etc/passwd")).isEqualTo("/etc/passwd");
    }

    @Test
    void listingTheRootListsTheWorkspaceNotTheContainerRoot() {
        sandbox.respond(cmd -> new ExecResult(
                0, "DIR:/workspace/reports\t1700000000\nFILE:/workspace/a.md\t12\t1700000000\n", "", false));

        LsResult result = filesystem.ls(bound, "/");

        assertThat(sandbox.commands())
                .singleElement()
                .satisfies(cmd -> assertThat(cmd).contains("'/workspace'/*"));
        assertThat(result.entries()).extracting(FileInfo::path).containsExactly("/reports", "/a.md");
    }

    @Test
    void grepResultsComeBackAsPlatformPaths() {
        sandbox.respond(cmd -> new ExecResult(0, "/workspace/notes/a.md:3:收入\n", "", false));

        GrepResult result = filesystem.grep(bound, "收入", "/notes", null);

        assertThat(sandbox.commands().getFirst()).contains("'/workspace/notes'");
        assertThat(result.matches()).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo("/notes/a.md");
            assertThat(m.line()).isEqualTo(3);
        });
    }

    @Test
    void globMatchesPathsRelativeToTheSearchDirectoryLikeThePlatformStore() {
        sandbox.respond(cmd -> new ExecResult(
                0,
                String.join(
                        "\n",
                        "/workspace/reports/q3.md\t10\t1700000000",
                        "/workspace/reports/2026/q4.md\t10\t1700000000",
                        "/workspace/reports/data.csv\t10\t1700000000",
                        "/workspace/reportsx/other.md\t10\t1700000000"),
                "",
                false));

        GlobResult nested = filesystem.glob(bound, "2026/*.md", "/reports");
        GlobResult anyDepth = filesystem.glob(bound, "*.md", "/reports");

        assertThat(sandbox.commands().getFirst()).contains("find '/workspace/reports'");
        assertThat(nested.matches()).extracting(FileInfo::path).containsExactly("/reports/2026/q4.md");
        assertThat(anyDepth.matches())
                .extracting(FileInfo::path)
                .containsExactly("/reports/2026/q4.md", "/reports/q3.md");
    }

    @Test
    void callsWithoutTheSandboxBoundToThisCallAreRejected() {
        SandboxBackedFilesystem harnessFs = new SandboxBackedFilesystem();
        harnessFs.setSandbox(sandbox);
        SandboxWorkspaceFilesystem guarded = new SandboxWorkspaceFilesystem(harnessFs);

        assertThatThrownBy(() -> guarded.execute(RuntimeContext.empty(), "ls", 10))
                .as("不能退回到共享实例上最后绑定的沙箱")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guarded.read(null, "/a.md", 0, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(sandbox.commands()).isEmpty();
    }
}
