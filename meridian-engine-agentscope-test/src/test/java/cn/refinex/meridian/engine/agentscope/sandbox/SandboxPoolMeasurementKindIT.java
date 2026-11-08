package cn.refinex.meridian.engine.agentscope.sandbox;

import static cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriverKindIT.in;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.port.InMemorySnapshotStore;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * M0 验证 P5（agent-sandbox、gVisor、预热池）的测量，结果写到 {@code target/p5/p5-results.json} 并打印，报告见
 * {@code docs/poc/P05-沙箱与预热池.md}。
 *
 * <ul>
 *   <li>认领：预热池（{@code sandbox-small}，2 个）就绪后连续认领 50 次，每次断言命中预热池中的沙箱；耗时为
 *       {@code SandboxDriver.create} 的全程（创建 claim、等待绑定与就绪、建立端口转发、{@code start()}）。
 *   <li>补充：每次认领返回后，到预热池的就绪数回到目标水位的时间。
 *   <li>快照与恢复：工作区 10 MB 与 100 MB（随机内容，100 个文件）各 5 次；快照存储为内存实现，不含对象存储的读写。
 *   <li>冷启动参照：预热池为 0 的 {@code medium} 认领 3 次。
 * </ul>
 */
@Tag("kind")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SandboxPoolMeasurementKindIT {

    private static final String NS = "meridian-sandbox";
    private static final int CLAIMS = 50;
    private static final int SNAPSHOT_ROUNDS = 5;
    private static final ResourceDefinitionContext WARM_POOL = new ResourceDefinitionContext.Builder()
            .withGroup("extensions.agents.x-k8s.io")
            .withVersion("v1beta1")
            .withPlural("sandboxwarmpools")
            .withKind("SandboxWarmPool")
            .withNamespaced(true)
            .build();
    private static final Map<String, Object> RESULTS = new LinkedHashMap<>();

    private static KubernetesClient kube;
    private static KubernetesSandboxDriver driver;
    private static final InMemorySnapshotStore SNAPSHOTS = new InMemorySnapshotStore();

    @BeforeAll
    static void connect() {
        kube = KubernetesSandboxDriverKindIT.kubeClient();
        driver = KubernetesSandboxDriverKindIT.newDriver(kube, SNAPSHOTS);
    }

    @AfterAll
    static void report() throws IOException {
        driver.close();
        kube.close();
        Path out = Path.of("target", "p5", "p5-results.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, toJson(RESULTS), StandardCharsets.UTF_8);
        System.out.println("P5 结果：" + out.toAbsolutePath() + "\n" + toJson(RESULTS));
    }

    @Test
    @Order(1)
    void warmPoolClaimAndReplenish() {
        int target = poolTarget("sandbox-small");
        List<Long> claims = new ArrayList<>();
        List<Long> replenishments = new ArrayList<>();
        for (int i = 0; i < CLAIMS; i++) {
            awaitPoolReady("sandbox-small", target, Duration.ofMinutes(3));
            Set<String> warm = poolSandboxes("sandbox-small");

            long start = System.nanoTime();
            SandboxHandle handle = in(driver.create(request(SandboxSize.SMALL)));
            long claimed = System.nanoTime();
            claims.add(millis(claimed - start));
            assertThat(warm).as("第 %d 次认领应命中预热池", i + 1).contains(handle.sandboxId());

            awaitPoolReady("sandbox-small", target, Duration.ofMinutes(3));
            replenishments.add(millis(System.nanoTime() - claimed));
            in(driver.delete(handle));
        }
        RESULTS.put("warm_claim_ms", stats(claims));
        RESULTS.put("replenish_ms", stats(replenishments));
        assertThat(claims).hasSize(CLAIMS);
    }

    @Test
    @Order(2)
    void snapshotAndRestore10MiB() throws Exception {
        measureSnapshot("10MiB", 100 * 1024);
    }

    @Test
    @Order(3)
    void snapshotAndRestore100MiB() throws Exception {
        measureSnapshot("100MiB", 1024 * 1024);
    }

    @Test
    @Order(4)
    void coldClaimForReference() {
        List<Long> colds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            SandboxHandle handle = in(driver.create(request(SandboxSize.MEDIUM)));
            colds.add(millis(System.nanoTime() - start));
            in(driver.delete(handle));
        }
        RESULTS.put("cold_claim_medium_ms", colds);
    }

    /** 100 个随机内容文件，每个 {@code fileBytes} 字节。 */
    private void measureSnapshot(String label, int fileBytes) throws Exception {
        List<Long> snapshots = new ArrayList<>();
        List<Long> restores = new ArrayList<>();
        long archiveBytes = 0;
        for (int round = 0; round < SNAPSHOT_ROUNDS; round++) {
            awaitPoolReady("sandbox-small", poolTarget("sandbox-small"), Duration.ofMinutes(3));
            SandboxHandle handle = in(driver.create(request(SandboxSize.SMALL)));
            SingleStartSandbox sandbox = driver.attach(handle.state());
            sandbox.exec(
                    null,
                    "mkdir -p data && for i in $(seq 1 100); do head -c " + fileBytes
                            + " /dev/urandom > data/f$i.bin; done",
                    300);
            String before = checksums(sandbox);

            long start = System.nanoTime();
            String key = in(driver.snapshot(handle));
            snapshots.add(millis(System.nanoTime() - start));
            archiveBytes = SNAPSHOTS.size(key);
            in(driver.delete(handle));

            awaitPoolReady("sandbox-small", poolTarget("sandbox-small"), Duration.ofMinutes(3));
            start = System.nanoTime();
            SandboxHandle restored = in(driver.restore(request(SandboxSize.SMALL), key));
            restores.add(millis(System.nanoTime() - start));
            assertThat(checksums(driver.attach(restored.state()))).isEqualTo(before);
            in(driver.delete(restored));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("archive_bytes", archiveBytes);
        result.put("snapshot_ms", stats(snapshots));
        result.put("restore_ms", stats(restores));
        RESULTS.put("workspace_" + label, result);
    }

    private static String checksums(SingleStartSandbox sandbox) throws Exception {
        return sandbox.exec(
                        null,
                        "find . -type f ! -path './.agentscope-tmp/*' -print0 | sort -z | xargs -0 sha256sum",
                        120)
                .stdout();
    }

    private static SandboxRequest request(SandboxSize size) {
        return new SandboxRequest("ses_01KINDMEASURE000000000000", size);
    }

    private static int poolTarget(String pool) {
        GenericKubernetesResource resource =
                kube.genericKubernetesResources(WARM_POOL).inNamespace(NS).withName(pool).get();
        return ((Number) ((Map<?, ?>) resource.get("spec")).get("replicas")).intValue();
    }

    /** 预热池中尚未被认领的沙箱（属主是 SandboxWarmPool）。 */
    private static Set<String> poolSandboxes(String pool) {
        return kube.resources(SandboxResource.class).inNamespace(NS).list().getItems().stream()
                .filter(s -> s.getMetadata().getOwnerReferences().stream()
                        .anyMatch(o -> "SandboxWarmPool".equals(o.getKind()) && pool.equals(o.getName())))
                .map(s -> s.getMetadata().getName())
                .collect(Collectors.toSet());
    }

    /** 等到预热池中未被认领且就绪的沙箱数达到目标；以资源状态为准，每 50 毫秒查询一次。 */
    private static void awaitPoolReady(String pool, int target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long ready = kube.resources(SandboxResource.class).inNamespace(NS).list().getItems().stream()
                    .filter(s -> s.getMetadata().getOwnerReferences().stream()
                            .anyMatch(o -> "SandboxWarmPool".equals(o.getKind()) && pool.equals(o.getName())))
                    .filter(s -> s.getMetadata().getDeletionTimestamp() == null)
                    .filter(s -> s.getStatus() != null
                            && s.getStatus().getConditions() != null
                            && s.getStatus().getConditions().stream()
                                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus())))
                    .count();
            if (ready >= target) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("预热池 " + pool + " 未在 " + timeout + " 内就绪");
    }

    private static long millis(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    /** 最小、最大、平均与按最近秩法取的 p50、p95、p99，附原始样本。 */
    private static Map<String, Object> stats(List<Long> samples) {
        List<Long> sorted = samples.stream().sorted().toList();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("n", sorted.size());
        stats.put("min", sorted.getFirst());
        stats.put("p50", percentile(sorted, 50));
        stats.put("p95", percentile(sorted, 95));
        stats.put("p99", percentile(sorted, 99));
        stats.put("max", sorted.getLast());
        stats.put("mean", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        stats.put("samples", samples);
        return stats;
    }

    private static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\": " + toJson(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SandboxPoolMeasurementKindIT::toJson).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(value);
    }
}
