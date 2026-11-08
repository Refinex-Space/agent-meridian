package cn.refinex.meridian.engine.agentscope.sandbox;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.error.SandboxUnavailableException;
import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandbox;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandboxClient;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandboxClientOptions;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandboxState;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxResource;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link SandboxDriver} 的实现：经 AgentScope 2.0.4 的 {@link KubernetesSandboxClient} 从 agent-sandbox 预热池认领沙箱
 * （{@code SandboxClaim}），工作区快照经 {@link PlatformSnapshotClient} 存入对象存储，见架构设计「会话沙箱」。
 *
 * <ul>
 *   <li>{@code create}：按规格选预热池（{@link SandboxDriverSettings#warmPoolName}）认领，给 claim 打会话标签，执行一次
 *       {@code start()}（建工作区根目录）。
 *   <li>{@code resume}：claim 与沙箱都还在才重新连接，否则以 {@link NotFoundException} 失败，调用方改为从快照恢复。
 *   <li>{@code restore}：认领新沙箱时把快照放进沙箱状态，{@code start()} 走 harness 的"从快照恢复"分支：下载归档、经
 *       {@code /upload} 传入、{@code tar -xf} 展开。
 *   <li>{@code snapshot}：{@code persistWorkspace()}（{@code tar -cf} 后经 {@code /download} 取回）写到
 *       {@code rt/snapshots/{tenant}/{project}/{session}/{snapshot_id}.tar}，租户、项目、会话取自 {@code PlatformContext}。
 *   <li>{@code delete}：关闭连接并删除 claim；claim 已不存在时无副作用。
 * </ul>
 *
 * <p>本节点上连接过的沙箱以 {@link SingleStartSandbox} 缓存（键为 claim 名），T12a 经 {@link #attach} 取得它放进
 * {@code SandboxContext.externalSandbox}；端口转发方式下每个沙箱持有一条到 sandbox-router 的转发，删除或 {@link #close()}
 * 时释放。所有操作都是阻塞的远程调用，经 {@link Blocking} 在虚拟线程上执行；基础设施失败统一转为
 * {@link SandboxUnavailableException}（可重试）。线程安全。
 */
public final class KubernetesSandboxDriver implements SandboxDriver, AutoCloseable {

    /** SandboxClaim 上记录会话 ID 的标签，便于排查。 */
    public static final String SESSION_LABEL = "meridian.refinex.cn/session-id";

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxDriver.class);
    private static final Pattern LABEL_VALUE = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9_.-]{0,61}[A-Za-z0-9])?");

    private final SandboxDriverSettings settings;
    private final KubernetesClient kube;
    private final KubernetesSandboxClient client;
    private final PlatformSnapshotClient snapshots;
    private final Map<String, SingleStartSandbox> live = new ConcurrentHashMap<>();

    /**
     * 创建驱动。
     *
     * @param settings 驱动配置
     * @param kube Kubernetes 客户端，需要沙箱命名空间中 {@code SandboxClaim}、{@code Sandbox} 的权限（chart 的
     *     {@code runtimeAccess}）；由调用方关闭
     * @param snapshotStore 快照存储端口
     */
    public KubernetesSandboxDriver(
            SandboxDriverSettings settings, KubernetesClient kube, SnapshotStorePort snapshotStore) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.kube = Objects.requireNonNull(kube, "kube");
        this.snapshots = new PlatformSnapshotClient(snapshotStore);
        this.client = new KubernetesSandboxClient(options(null));
    }

    @Override
    public Mono<SandboxHandle> create(SandboxRequest request) {
        Objects.requireNonNull(request, "request");
        return guard(Blocking.call(() -> handle(claim(request, new NoopSnapshotSpec()))), "认领沙箱失败");
    }

    @Override
    public Mono<SandboxHandle> resume(String state) {
        Objects.requireNonNull(state, "state");
        return guard(Blocking.call(() -> handle(attach(state))), "重新连接沙箱失败");
    }

    @Override
    public Mono<SandboxHandle> restore(SandboxRequest request, String snapshotKey) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshotKey, "snapshotKey");
        return guard(
                Blocking.call(() -> {
                    if (!snapshots.exists(snapshotKey)) {
                        throw new NotFoundException("快照不存在：" + snapshotKey);
                    }
                    // 忽略 harness 传入的随机 ID：快照的 ID 就是对象键
                    return handle(claim(request, ignoredId -> snapshots.at(snapshotKey)));
                }),
                "从快照恢复沙箱失败");
    }

    @Override
    public Mono<String> snapshot(SandboxHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return guard(
                Blocking.call(() -> {
                    String key = PlatformSnapshotClient.key(PlatformContexts.current(), Ids.newUlid());
                    SingleStartSandbox sandbox = attach(handle.state());
                    try (InputStream archive = sandbox.persistWorkspace()) {
                        snapshots.at(key).persist(archive);
                    }
                    return key;
                }),
                "保存沙箱快照失败");
    }

    @Override
    public Mono<Void> delete(SandboxHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return guard(
                Blocking.run(() -> {
                    String claim = state(handle.state()).getClaimName();
                    SingleStartSandbox sandbox = live.remove(claim);
                    if (sandbox != null) {
                        closeConnection(sandbox);
                    }
                    kube.resources(SandboxClaim.class)
                            .inNamespace(settings.namespace())
                            .withName(claim)
                            .delete();
                }),
                "删除沙箱失败");
    }

    /**
     * 取得可以交给 harness 的沙箱（{@code SandboxContext.externalSandbox}），本节点连接过的直接复用，否则按状态重新连接并启动。
     * 阻塞调用，在虚拟线程上执行。
     *
     * @param state 序列化后的沙箱状态（{@link SandboxHandle#state()}）
     * @return 已启动的沙箱
     * @throws NotFoundException claim 或沙箱已不存在时
     */
    public SingleStartSandbox attach(String state) {
        KubernetesSandboxState parsed = state(state);
        String claim = parsed.getClaimName();
        if (!exists(parsed)) {
            SingleStartSandbox stale = live.remove(claim);
            if (stale != null) {
                closeConnection(stale);
            }
            throw new NotFoundException("沙箱已不存在：" + claim);
        }
        return live.computeIfAbsent(claim, ignored -> {
            Sandbox sandbox = client.resume(parsed);
            if (sandbox instanceof KubernetesSandbox k8s) {
                String podIp = k8s.getSdkSandbox().getPodIp();
                if (podIp != null) {
                    parsed.setPodIP(podIp);
                }
            }
            return started(new SingleStartSandbox(sandbox));
        });
    }

    /** 关闭本节点到各沙箱的连接（端口转发），不删除沙箱。 */
    @Override
    public void close() {
        live.values().forEach(KubernetesSandboxDriver::closeConnection);
        live.clear();
    }

    private SingleStartSandbox claim(SandboxRequest request, SandboxSnapshotSpec snapshotSpec) {
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot(settings.workspaceRoot());
        KubernetesSandboxClientOptions options = options(settings.warmPoolName(request.size()));
        Sandbox sandbox = client.create(workspace, snapshotSpec, options);
        String claim = ((KubernetesSandboxState) sandbox.getState()).getClaimName();
        try {
            label(claim, request.sessionId());
            SingleStartSandbox single = started(new SingleStartSandbox(sandbox));
            live.put(claim, single);
            return single;
        } catch (RuntimeException e) {
            try {
                sandbox.shutdown();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static SingleStartSandbox started(SingleStartSandbox sandbox) {
        try {
            sandbox.start();
            return sandbox;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("沙箱启动失败", e);
        }
    }

    private void label(String claim, String sessionId) {
        if (!LABEL_VALUE.matcher(sessionId).matches()) {
            log.warn("会话 ID 不是合法的标签值，claim {} 不打会话标签", claim);
            return;
        }
        kube.resources(SandboxClaim.class)
                .inNamespace(settings.namespace())
                .withName(claim)
                .patch(
                        PatchContext.of(PatchType.JSON_MERGE),
                        "{\"metadata\":{\"labels\":{\"" + SESSION_LABEL + "\":\"" + sessionId + "\"}}}");
    }

    private boolean exists(KubernetesSandboxState state) {
        String ns = state.getNamespace();
        return kube.resources(SandboxClaim.class)
                                .inNamespace(ns)
                                .withName(state.getClaimName())
                                .get()
                        != null
                && state.getSandboxName() != null
                && kube.resources(SandboxResource.class)
                                .inNamespace(ns)
                                .withName(state.getSandboxName())
                                .get()
                        != null;
    }

    private SandboxHandle handle(SingleStartSandbox sandbox) {
        KubernetesSandboxState state = (KubernetesSandboxState) sandbox.getState();
        if (state.getPodIP() == null) {
            throw new IllegalStateException("沙箱没有 Pod IP：" + state.getClaimName());
        }
        return new SandboxHandle(state.getSandboxName(), state.getPodIP(), client.serializeState(state));
    }

    private KubernetesSandboxState state(String json) {
        return (KubernetesSandboxState) client.deserializeState(json, new RemoteSnapshotSpec(snapshots));
    }

    /**
     * 完整的客户端选项。2.0.4 的 {@code KubernetesSandboxClient} 合并单次选项时，非空字段一律覆盖默认值，而命名空间、
     * 工作区根目录、文件接口目录、端口都有非空缺省，所以单次选项也必须完整设置；超时只取默认选项。
     */
    private KubernetesSandboxClientOptions options(String warmPoolName) {
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setKubernetesClient(kube);
        options.setNamespace(settings.namespace());
        options.setWarmPoolName(warmPoolName);
        options.setWorkspaceRoot(settings.workspaceRoot());
        options.setFileApiBaseDir(settings.workspaceRoot());
        options.setServerPort(8888);
        switch (settings.connection()) {
            case SandboxDriverSettings.PortForward ignored -> {
                // KubernetesSandboxClient 在既无 apiUrl 也无 gatewayName 时用端口转发，router 在沙箱命名空间
            }
            case SandboxDriverSettings.Direct direct -> options.setApiUrl(direct.url());
            case SandboxDriverSettings.Gateway gateway -> {
                options.setGatewayName(gateway.name());
                options.setGatewayNamespace(gateway.gatewayNamespace());
                options.setGatewayScheme(gateway.scheme());
            }
        }
        long httpSeconds = settings.execTimeout()
                .plus(SandboxDriverSettings.HTTP_TIMEOUT_MARGIN)
                .toSeconds();
        options.setSandboxReadyTimeoutSeconds(settings.readyTimeout().toSeconds());
        options.setPerAttemptTimeoutSeconds(httpSeconds);
        options.setRequestTimeoutSeconds(httpSeconds);
        return options;
    }

    private static void closeConnection(SingleStartSandbox sandbox) {
        if (sandbox.delegate() instanceof KubernetesSandbox k8s) {
            k8s.getSdkSandbox().closeConnection();
        }
    }

    private static <T> Mono<T> guard(Mono<T> operation, String detail) {
        return operation.onErrorMap(
                e -> !(e instanceof MeridianException), e -> new SandboxUnavailableException(detail, e));
    }
}
