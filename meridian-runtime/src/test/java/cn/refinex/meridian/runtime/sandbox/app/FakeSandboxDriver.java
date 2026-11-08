package cn.refinex.meridian.runtime.sandbox.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * 内存中的 {@link SandboxDriver}：每个沙箱是一份 {@code outputs/} 文件表，快照是文件表的副本（同时往对象存储写一个占位对象，便于断言
 * 快照的删除）。{@link #holdResume}、{@link #holdSnapshot} 让一次操作停在中途，用来制造回收与轮次开始的竞争。
 */
final class FakeSandboxDriver implements SandboxDriver {

    /** 一个沙箱：资源名、Pod IP 与产物文件。 */
    record Box(String id, String podIp, Map<String, byte[]> outputs) {}

    final Map<String, Box> live = new ConcurrentHashMap<>();
    final Map<String, Map<String, byte[]>> snapshots = new ConcurrentHashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<List<SkillDefinition>> skillSyncs = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile String nextPodIp;
    private final ObjectStore objects;
    private volatile Gate resumeGate;
    private volatile Gate snapshotGate;

    FakeSandboxDriver(ObjectStore objects) {
        this.objects = objects;
    }

    /** 下一次 {@code resume} 停在中途，直到 {@link Gate#open()}。 */
    Gate holdResume() {
        resumeGate = new Gate();
        return resumeGate;
    }

    /** 下一次 {@code snapshot} 停在中途，直到 {@link Gate#open()}。 */
    Gate holdSnapshot() {
        snapshotGate = new Gate();
        return snapshotGate;
    }

    Box box(SandboxHandle handle) {
        return live.get(handle.state());
    }

    /** 下一个新建或恢复的沙箱使用这个 Pod IP（模拟 IP 被新 Pod 复用）。 */
    void nextPodIp(String ip) {
        nextPodIp = ip;
    }

    /** 沙箱的 Pod 在平台之外被重建、换了 IP：之后的 {@code resume} 返回新 IP。 */
    void changePodIp(SandboxHandle handle, String ip) {
        live.computeIfPresent(handle.state(), (state, box) -> new Box(box.id(), ip, box.outputs()));
    }

    @Override
    public Mono<SandboxHandle> create(SandboxRequest request) {
        return Mono.fromSupplier(() -> {
            calls.add("create");
            return newBox(new ConcurrentHashMap<>());
        });
    }

    @Override
    public Mono<SandboxHandle> resume(String state) {
        return Mono.fromSupplier(() -> {
            calls.add("resume");
            Gate gate = resumeGate;
            if (gate != null) {
                resumeGate = null;
                gate.pass();
            }
            Box box = live.get(state);
            if (box == null) {
                throw new NotFoundException("沙箱已不存在：" + state);
            }
            return new SandboxHandle(box.id(), box.podIp(), state);
        });
    }

    @Override
    public Mono<SandboxHandle> restore(SandboxRequest request, String snapshotKey) {
        return Mono.fromSupplier(() -> {
            calls.add("restore");
            Map<String, byte[]> files = snapshots.get(snapshotKey);
            if (files == null) {
                throw new NotFoundException("快照不存在：" + snapshotKey);
            }
            return newBox(new ConcurrentHashMap<>(files));
        });
    }

    @Override
    public Mono<String> snapshot(SandboxHandle handle) {
        return Mono.deferContextual(view -> Mono.fromSupplier(() -> {
            calls.add("snapshot");
            Gate gate = snapshotGate;
            if (gate != null) {
                snapshotGate = null;
                gate.pass();
            }
            Box box = live.get(handle.state());
            if (box == null) {
                throw new NotFoundException("沙箱已不存在：" + handle.sandboxId());
            }
            PlatformContext context = PlatformContexts.find(view).orElseThrow();
            String key = SnapshotStorePort.KEY_PREFIX + context.tenantId() + "/" + context.projectId() + "/"
                    + context.sessionId() + "/" + Ids.newUlid() + ".tar";
            snapshots.put(key, new ConcurrentHashMap<>(box.outputs()));
            objects.put(key, "tar".getBytes(StandardCharsets.UTF_8), "application/x-tar");
            return key;
        }));
    }

    @Override
    public Mono<Void> delete(SandboxHandle handle) {
        return Mono.fromRunnable(() -> {
            calls.add("delete");
            live.remove(handle.state());
        });
    }

    @Override
    public Mono<Boolean> syncSkills(SandboxHandle handle, List<SkillDefinition> skills) {
        return Mono.fromSupplier(() -> {
            skillSyncs.add(List.copyOf(skills));
            return true;
        });
    }

    @Override
    public Mono<List<OutputFile>> listOutputs(SandboxHandle handle) {
        return Mono.fromSupplier(() -> box(handle).outputs().entrySet().stream()
                .map(e -> new OutputFile(e.getKey(), e.getValue().length, sha256(e.getValue())))
                .toList());
    }

    @Override
    public Mono<byte[]> readOutput(SandboxHandle handle, String path) {
        return Mono.fromSupplier(() -> box(handle).outputs().get(path));
    }

    private SandboxHandle newBox(Map<String, byte[]> outputs) {
        int n = sequence.incrementAndGet();
        String ip = nextPodIp;
        nextPodIp = null;
        Box box = new Box("sbx-" + n, ip != null ? ip : "10.0.0." + n, outputs);
        live.put("state-" + n, box);
        return new SandboxHandle(box.id(), box.podIp(), "state-" + n);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 让一次操作停在中途的闸门。 */
    static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pass() {
            entered.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("闸门 30 秒没有放行");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("操作 30 秒没有到达闸门");
            }
        }

        void open() {
            released.countDown();
        }
    }
}
