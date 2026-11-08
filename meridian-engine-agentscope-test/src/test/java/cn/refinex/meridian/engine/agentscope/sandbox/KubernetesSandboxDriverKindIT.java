package cn.refinex.meridian.engine.agentscope.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemorySnapshotStore;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link KubernetesSandboxDriver} 在本机 kind 集群上的端到端验证（T11-A05）：认领、执行命令、上传下载、快照、删除、
 * 从快照恢复后文件一致，以及另一个驱动实例按序列化状态重新连接。
 *
 * <p>前提：kind 集群 {@code meridian}（{@code scripts/dev/kind-gvisor.sh}）、agent-sandbox 控制器
 * （{@code scripts/dev/install-agent-sandbox.sh}）与沙箱 chart（{@code scripts/dev/deploy-sandbox.sh}）。连接方式为端口转发。
 * 运行：{@code ./mvnw -Pkind -pl meridian-engine-agentscope-test -am verify}。
 */
@Tag("kind")
class KubernetesSandboxDriverKindIT {

    static final String KUBE_CONTEXT = "kind-meridian";
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final PlatformContext CONTEXT = PlatformContext.builder()
            .tenantId("ten_kind")
            .projectId("prj_kind")
            .sessionId("ses_01KINDDRIVERTEST0000000000")
            .build();

    private static KubernetesClient kube;
    private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
    private final List<SandboxHandle> created = new ArrayList<>();
    private KubernetesSandboxDriver driver;

    @BeforeAll
    static void connect() {
        kube = kubeClient();
    }

    @AfterAll
    static void disconnect() {
        kube.close();
    }

    static KubernetesClient kubeClient() {
        return new KubernetesClientBuilder()
                .withConfig(Config.autoConfigure(KUBE_CONTEXT))
                .build();
    }

    static KubernetesSandboxDriver newDriver(KubernetesClient kube, InMemorySnapshotStore snapshots) {
        return new KubernetesSandboxDriver(
                SandboxDriverSettings.defaults(new SandboxDriverSettings.PortForward()), kube, snapshots);
    }

    @AfterEach
    void cleanUp() {
        for (SandboxHandle handle : created) {
            in(driver.delete(handle));
        }
        driver.close();
    }

    @Test
    void claimExecTransferSnapshotDeleteAndRestore() throws Exception {
        driver = newDriver(kube, snapshots);

        SandboxHandle handle = track(in(driver.create(new SandboxRequest(CONTEXT.sessionId(), SandboxSize.SMALL))));
        assertThat(handle.podIp()).matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
        SandboxClaim claim = claimOf(handle);
        assertThat(claim.getMetadata().getLabels())
                .containsEntry(KubernetesSandboxDriver.SESSION_LABEL, CONTEXT.sessionId());
        assertThat(claim.getSpec().getWarmPoolRef().getName()).isEqualTo("sandbox-small");

        SingleStartSandbox sandbox = driver.attach(handle.state());
        ExecResult kernel = sandbox.exec(null, "uname -r; id -u; pwd", 30);
        assertThat(kernel.stdout()).contains("gvisor").contains("1000").contains("/workspace");

        // 工作区：文本、子目录中的二进制文件（随机 2 MB）、经文件接口上传的文件
        sandbox.exec(
                null,
                "echo hello > a.txt && mkdir -p data/nested && head -c 2097152 /dev/urandom > data/nested/r.bin",
                60);
        sandbox.uploadFile("/workspace/up/中文.txt", "上传内容".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(sandbox.downloadFile("/workspace/up/中文.txt"), StandardCharsets.UTF_8))
                .isEqualTo("上传内容");
        assertThat(sandbox.downloadFile("/workspace/a.txt")).isEqualTo("hello\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> sandbox.exec(null, "exit 3", 10))
                .isInstanceOfSatisfying(
                        SandboxException.ExecException.class,
                        e -> assertThat(e.getExitCode()).isEqualTo(3));
        String before = checksums(sandbox);

        String key = in(driver.snapshot(handle));
        assertThat(key)
                .startsWith("rt/snapshots/ten_kind/prj_kind/" + CONTEXT.sessionId() + "/")
                .endsWith(".tar");
        assertThat(snapshots.size(key)).isGreaterThan(2_000_000);

        in(driver.delete(handle));
        created.remove(handle);
        assertThat(claimOf(handle)).isNull();
        assertThatThrownBy(() -> in(driver.resume(handle.state()))).isInstanceOf(NotFoundException.class);

        SandboxHandle restored =
                track(in(driver.restore(new SandboxRequest(CONTEXT.sessionId(), SandboxSize.SMALL), key)));
        assertThat(restored.sandboxId()).isNotEqualTo(handle.sandboxId());
        assertThat(checksums(driver.attach(restored.state()))).isEqualTo(before);
    }

    @Test
    void anotherDriverResumesFromSerializedState() throws Exception {
        driver = newDriver(kube, snapshots);
        SandboxHandle handle = track(in(driver.create(new SandboxRequest(CONTEXT.sessionId(), SandboxSize.SMALL))));
        driver.attach(handle.state()).exec(null, "echo kept > kept.txt", 10);

        // 另一个 runtime 节点：新的驱动实例只拿到序列化状态
        try (KubernetesClient otherKube = kubeClient();
                KubernetesSandboxDriver other = newDriver(otherKube, snapshots)) {
            SandboxHandle resumed = in(other.resume(handle.state()));
            assertThat(resumed.sandboxId()).isEqualTo(handle.sandboxId());
            assertThat(resumed.podIp()).isEqualTo(handle.podIp());
            assertThat(other.attach(resumed.state())
                            .exec(null, "cat kept.txt", 10)
                            .stdout())
                    .isEqualTo("kept\n");
        }
    }

    @Test
    void restoreFromMissingSnapshotFailsWithNotFound() {
        driver = newDriver(kube, snapshots);

        assertThatThrownBy(() -> in(driver.restore(
                        new SandboxRequest(CONTEXT.sessionId(), SandboxSize.SMALL),
                        "rt/snapshots/ten_kind/prj_kind/ses_x/missing.tar")))
                .isInstanceOf(NotFoundException.class);
    }

    /** 工作区内全部文件（不含快照用的临时目录）的 SHA-256 清单。 */
    private static String checksums(SingleStartSandbox sandbox) throws Exception {
        return sandbox.exec(
                        null, "find . -type f ! -path './.agentscope-tmp/*' -print0 | sort -z | xargs -0 sha256sum", 60)
                .stdout();
    }

    private SandboxHandle track(SandboxHandle handle) {
        created.add(handle);
        return handle;
    }

    private static SandboxClaim claimOf(SandboxHandle handle) {
        String claimName = handle.state().replaceAll("(?s).*\"claimName\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return kube.resources(SandboxClaim.class)
                .inNamespace("meridian-sandbox")
                .withName(claimName)
                .get();
    }

    static <T> T in(Mono<T> operation) {
        return operation.contextWrite(PlatformContexts.write(CONTEXT)).block(TIMEOUT);
    }
}
