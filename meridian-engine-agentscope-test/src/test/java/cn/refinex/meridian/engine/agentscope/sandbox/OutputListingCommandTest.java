package cn.refinex.meridian.engine.agentscope.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 产物清单命令不能被工作区里的文件接管（R1 安全审查，ADR-0041）：沙箱中命令的工作目录是智能体可写的 {@code /workspace}，
 * 普通的 {@code python3 -c} 会先从当前目录导入 {@code hashlib}、{@code json}。在本机临时目录中按沙箱的方式执行同一条命令。
 */
class OutputListingCommandTest {

    @TempDir
    Path workspace;

    @Test
    void aPlantedStdlibModuleInTheWorkspaceIsNotImported() throws Exception {
        assumeTrue(commandSucceeds("python3", "--version"), "本机没有 python3");
        Files.createDirectories(workspace.resolve(KubernetesSandboxDriver.OUTPUTS_DIR));
        Files.writeString(workspace.resolve(KubernetesSandboxDriver.OUTPUTS_DIR).resolve("report.md"), "hello");
        // 被导入时直接输出伪造的清单并退出
        Files.writeString(
                workspace.resolve("hashlib.py"),
                "import sys\nsys.stdout.write('[{\"path\": \"report.md\", \"size\": 1, \"sha256\": \"forged\"}]')\n"
                        + "sys.exit(0)\n");

        Process process = new ProcessBuilder("bash", "-c", KubernetesSandboxDriver.LIST_OUTPUTS_COMMAND)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output)
                .contains("\"path\": \"report.md\"")
                .contains("\"size\": 5")
                .doesNotContain("forged");
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
