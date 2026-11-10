package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriver;
import cn.refinex.meridian.engine.agentscope.sandbox.SingleStartSandbox;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.egress.app.EgressRevocations;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P14 出网控制在本机 kind 集群上的端到端验证（T12b-A01）：集群中的 Envoy 出网代理经 {@code host.docker.internal:18080} 回调本测试
 * 启动的 runtime（宿主拓扑，{@code values-kind.yaml}）；回调带的服务 JWT 由代理用客户端密钥在 {@code host.docker.internal:18081}
 * 换取（T14b，ADR-0044），这里由本测试启动的 {@link TestTokenIssuer} 代替 control-plane，runtime 经它的 JWKS 校验；沙箱是从 agent-sandbox 预热池认领的真实 gVisor Pod，白名单内外的目标是
 * {@code deploy/kind/egress-echo.yaml} 中的 {@code allowed-echo} 与 {@code denied-echo}。
 *
 * <p>四条：白名单内的域名经代理可以访问（明文 HTTP 与 CONNECT 隧道）；其他域名被拒；沙箱删除后同 IP 的新 Pod 不继承旧授权（在 30 秒
 * 缓存期内）；绕过代理的直连被 NetworkPolicy 阻断。每条的命令与输出写进日志，供 {@code docs/poc/P14-出网控制.md} 引用。
 *
 * <p>前提：kind 集群 {@code meridian} 已按根 {@code CLAUDE.md} 的环境脚本建好，并用 {@code scripts/dev/deploy-sandbox.sh} 部署了
 * 沙箱 chart（含出网代理、凭据 Secret 与两个目标服务）；宿主的 18080、18081 端口空闲（不要同时运行 {@code run-runtime.sh --server.port=18080} 与
 * {@code run-control-plane.sh}）。
 * 运行：{@code ./mvnw -Pkind -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=EgressProxyKindIT}。
 */
@Tag("kind")
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DirtiesContext
class EgressProxyKindIT {

    static final String KUBE_CONTEXT = "kind-meridian";
    static final String NAMESPACE = "meridian-sandbox";

    /** {@code values-kind.yaml} 中 {@code egressProxy.authz.host.port}：代理经 {@code host.docker.internal} 回调这个端口。 */
    static final int RUNTIME_PORT = 18080;

    /** 沙箱模板中的代理地址（{@code values.yaml} 的 {@code egressProxy.url}）。 */
    static final String PROXY_URL = "http://meridian-egress-proxy." + NAMESPACE + ".svc.cluster.local:10000";

    /** {@code values-kind.yaml} 中 {@code egressProxy.authz.controlPlane.host.port}：代理经 {@code host.docker.internal} 换取令牌。 */
    static final int CONTROL_PLANE_PORT = 18081;

    /** 出网代理的客户端密钥（{@code deploy-sandbox.sh} 创建的 Secret）。 */
    static final String CLIENT_SECRET = "meridian-egress-proxy-client";

    /** 代替 control-plane 的令牌签发方：监听全部地址，集群中的代理经宿主地址访问。 */
    private static final TestTokenIssuer TOKENS = startTokenIssuer();

    static final String ALLOWED = "allowed-echo.meridian-egress-test.svc.cluster.local";
    static final String DENIED = "denied-echo.meridian-egress-test.svc.cluster.local";

    static final String AGENT_VERSION = "agv_egress_kind_1";

    /** 白名单只有 {@code allowed-echo} 的环境。 */
    static final String ENV_ALLOWED = "env_egress_kind_allowed";

    /** 白名单只有 {@code denied-echo} 的环境：同一 IP 换了主人之后按新主人的白名单判定。 */
    static final String ENV_OTHER = "env_egress_kind_other";

    /** 出网授权按源 IP 的缓存时长（架构设计「沙箱出网」）。 */
    static final Duration GRANT_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(EgressProxyKindIT.class);

    private static final Path CONFIG = writeConfig();

    private static KubernetesClient kube;

    @Autowired
    private SandboxService sandboxes;

    @Autowired
    private SandboxDriver driver;

    @Autowired
    private EgressRevocations revocations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("server.port", () -> RUNTIME_PORT);
        registry.add("meridian.runtime.worker.enabled", () -> "false");
        registry.add("meridian.runtime.sandbox.enabled", () -> "true");
        registry.add("meridian.runtime.sandbox.kube-context", () -> KUBE_CONTEXT);
        registry.add("meridian.runtime.dev-agent-config.directory", () -> CONFIG.toString());
        registry.add("meridian.security.service-jwt.jwks-url", TOKENS::jwksUrl);
    }

    @AfterEach
    void cleanUp() {
        for (Runnable cleanup : cleanups.reversed()) {
            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.warn("清理失败", e);
            }
        }
    }

    @AfterAll
    static void disconnect() {
        TOKENS.close();
        if (kube != null) {
            kube.close();
        }
    }

    @Test
    void p14EgressIsAllowlistedPerSessionAndCannotBypassTheProxy() throws Exception {
        TestSession owner = session(ENV_ALLOWED);
        SandboxHandle handle = claim(owner);
        String ip = handle.podIp();
        cleanups.add(() -> deleteSandbox(owner));
        SingleStartSandbox sandbox = ((KubernetesSandboxDriver) driver).attach(handle.state());
        log.info("P14 会话 {} 的沙箱 {}，Pod IP {}", owner.sessionId(), handle.sandboxId(), ip);

        // 1. 白名单内的域名可以访问：明文 HTTP 经代理转发，CONNECT 隧道（HTTPS 的走法，curl -p 对明文也建隧道）
        assertThat(inSandbox(sandbox, curl("", "http://" + ALLOWED + "/"))).isEqualTo("200 allowed-echo");
        assertThat(inSandbox(sandbox, tunnel("http://" + ALLOWED + "/"))).isEqualTo("200/200 allowed-echo");
        // 代理的回调带服务 JWT：它用客户端密钥换取过令牌（runtime 只接受 egress-proxy 的有效令牌，T14b）
        assertThat(TOKENS.tokenRequests()).as("出网代理换取服务 JWT 的次数").isPositive();
        log.info("P14 出网代理换取服务 JWT {} 次", TOKENS.tokenRequests());

        // 2. 其他域名被拒：明文 HTTP 得到代理的 403，CONNECT 隧道建立失败（代理回 403）
        assertThat(inSandbox(sandbox, curl("", "http://" + DENIED + "/"))).startsWith("403 ");
        assertThat(inSandbox(sandbox, tunnel("http://" + DENIED + "/"))).isEqualTo("403/000");

        // 4. 绕过代理的直连被 NetworkPolicy 阻断：服务名与 Pod IP 都连不上，只有 DNS 可用
        String echoPodIp = echoPodIp();
        assertThat(inSandbox(sandbox, curl("--noproxy '*'", "http://" + ALLOWED + "/")))
                .isEqualTo("000");
        assertThat(inSandbox(sandbox, curl("--noproxy '*'", "http://" + echoPodIp + ":8080/")))
                .isEqualTo("000");
        assertThat(inSandbox(sandbox, "getent hosts " + ALLOWED + " >/dev/null && echo resolved"))
                .isEqualTo("resolved");

        // 3. 沙箱删除后同 IP 的新 Pod 不继承旧授权：最后一次放行后立即删除沙箱（清空 Pod IP），让一个新 Pod 拿到同一 IP
        Instant lastAllowed = Instant.now();
        assertThat(inSandbox(sandbox, curl("", "http://" + ALLOWED + "/"))).isEqualTo("200 allowed-echo");
        deleteSandbox(owner);
        awaitNoPodWithIp(ip);
        String newPod = podWithIp(ip);
        Duration elapsed = Duration.between(lastAllowed, Instant.now());
        log.info("P14 同 IP 的新 Pod {} 在最后一次放行后 {} 毫秒就绪", newPod, elapsed.toMillis());
        String fromNewPod = inPod(newPod, curl("", "http://" + ALLOWED + "/"));
        assertThat(fromNewPod).as("新 Pod 不属于任何会话").startsWith("403 ");
        assertThat(elapsed).as("在 30 秒缓存期内仍被拒绝，说明旧授权已失效而不是过期").isLessThan(GRANT_TTL);

        // 同一 IP 记到另一个会话名下（相当于新会话认领了这个 Pod）：按新会话的白名单判定
        TestSession next = session(ENV_OTHER);
        DbAssertions.update("UPDATE rt.sessions SET sandbox_pod_ip = ? WHERE id = ?", ip, next.sessionId());
        cleanups.add(() ->
                DbAssertions.update("UPDATE rt.sessions SET sandbox_pod_ip = NULL WHERE id = ?", next.sessionId()));
        revocations.podIpChanged(null, ip);
        assertThat(inPod(newPod, curl("", "http://" + DENIED + "/"))).isEqualTo("200 denied-echo");
        assertThat(inPod(newPod, curl("", "http://" + ALLOWED + "/"))).startsWith("403 ");
    }

    // ---- 会话与沙箱 ----

    TestSession session(String environmentId) {
        return TestSessions.create(Clock.systemUTC(), AGENT_VERSION, environmentId, 1);
    }

    SandboxHandle claim(TestSession session) {
        PlatformContext platform = session.context().toBuilder()
                .threadId(session.mainThreadId())
                .turnId(Ids.newId(IdPrefix.TURN))
                .leaseEpoch(1L)
                .interaction(Interaction.ATTENDED)
                .build();
        TurnContext turn = new TurnContext(
                platform,
                "u_egress",
                AGENT_VERSION,
                ENV_ALLOWED,
                1,
                new PermissionPolicy(PermissionMode.DEFAULT, List.of()),
                null,
                null,
                null);
        return PlatformContexts.call(platform, () -> sandboxes.prepare(turn)).orElseThrow();
    }

    void deleteSandbox(TestSession session) {
        PlatformContexts.run(session.context(), () -> sandboxes.deleteSessionSandbox(session.sessionId()));
    }

    // ---- 命令 ----

    /** 输出 {@code <状态码> <响应体>}（去掉首尾空白）：连接失败时只有状态码 000。 */
    static String curl(String options, String url) {
        return curl(options, url, "%{http_code}");
    }

    /**
     * 经代理的 CONNECT 隧道请求（HTTPS 的走法；{@code curl -p} 对明文 URL 同样先建隧道），输出 {@code <CONNECT 的状态码>/<状态码> <响应体>}。
     */
    static String tunnel(String url) {
        return curl("-p", url, "%{http_connect}/%{http_code}");
    }

    private static String curl(String options, String url, String format) {
        return "curl -sS -m 5 " + options + " -o /tmp/p14-body -w '" + format + "' '" + url + "' 2>/dev/null;"
                + " printf ' '; cat /tmp/p14-body 2>/dev/null; rm -f /tmp/p14-body";
    }

    static String inSandbox(SingleStartSandbox sandbox, String command) {
        try {
            String output = sandbox.exec(null, command, 30).stdout().strip();
            log.info("P14 沙箱内 $ {}\n{}", command, output);
            return output;
        } catch (Exception e) {
            throw new AssertionError("沙箱内执行失败：" + command, e);
        }
    }

    static String inPod(String pod, String command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExecWatch watch = kube().pods()
                .inNamespace(NAMESPACE)
                .withName(pod)
                .writingOutput(out)
                .writingError(new ByteArrayOutputStream())
                .exec("bash", "-c", command)) {
            watch.exitCode().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Pod 内执行失败：" + command, e);
        }
        String output = out.toString(StandardCharsets.UTF_8).strip();
        log.info("P14 Pod {} 内 $ {}\n{}", pod, command, output);
        return output;
    }

    // ---- 集群 ----

    static KubernetesClient kube() {
        if (kube == null) {
            kube = new KubernetesClientBuilder()
                    .withConfig(Config.autoConfigure(KUBE_CONTEXT))
                    .build();
        }
        return kube;
    }

    static TestTokenIssuer startTokenIssuer() {
        try {
            return TestTokenIssuer.start(java.net.InetAddress.getByName("0.0.0.0"), CONTROL_PLANE_PORT)
                    .client("egress-proxy", proxyClientSecret());
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static String proxyClientSecret() {
        Secret secret =
                kube().secrets().inNamespace(NAMESPACE).withName(CLIENT_SECRET).get();
        if (secret == null) {
            throw new IllegalStateException("缺少 Secret " + CLIENT_SECRET + "，先运行 scripts/dev/deploy-sandbox.sh");
        }
        return new String(Base64.getDecoder().decode(secret.getData().get("client-secret")), StandardCharsets.UTF_8);
    }

    static String echoPodIp() {
        return kube().pods()
                .inNamespace("meridian-egress-test")
                .withLabel("app.kubernetes.io/name", "egress-echo")
                .list()
                .getItems()
                .getFirst()
                .getStatus()
                .getPodIP();
    }

    static Optional<Pod> podByIp(String ip) {
        return kube().pods().inNamespace(NAMESPACE).list().getItems().stream()
                .filter(pod -> ip.equals(pod.getStatus().getPodIP()))
                .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                .findFirst();
    }

    static void awaitNoPodWithIp(String ip) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> kube().pods().inNamespace(NAMESPACE).list().getItems().stream()
                        .noneMatch(pod -> ip.equals(pod.getStatus().getPodIP())));
    }

    /**
     * 让一个新 Pod 拿到这个 IP：预热池补充的沙箱可能已经拿到它；否则新建一个带沙箱标签的探针 Pod，用 Calico 的
     * {@code cni.projectcalico.org/ipAddrs} 注解指定 IP。
     *
     * @return 持有该 IP 的新 Pod 名
     */
    String podWithIp(String ip) {
        Optional<Pod> existing = podByIp(ip);
        if (existing.isPresent()) {
            return existing.get().getMetadata().getName();
        }
        String name = "p14-probe-" + Long.toString(System.nanoTime(), 36);
        kube().pods()
                .inNamespace(NAMESPACE)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withLabels(Map.of(
                                "app.kubernetes.io/name", "meridian-sandbox",
                                "app.kubernetes.io/component", "sandbox"))
                        .withAnnotations(Map.of("cni.projectcalico.org/ipAddrs", "[\"" + ip + "\"]"))
                        .endMetadata()
                        .withNewSpec()
                        .withAutomountServiceAccountToken(false)
                        .withTerminationGracePeriodSeconds(1L)
                        .withNewSecurityContext()
                        .withRunAsNonRoot(true)
                        .withRunAsUser(1000L)
                        .withNewSeccompProfile()
                        .withType("RuntimeDefault")
                        .endSeccompProfile()
                        .endSecurityContext()
                        .addNewContainer()
                        .withName("probe")
                        .withImage("agent-meridian/sandbox-runtime:0.1.0")
                        .withImagePullPolicy("Never")
                        .withCommand("sleep", "600")
                        // 与沙箱模板相同：curl 对 http:// 只认小写的 http_proxy
                        .addNewEnv()
                        .withName("HTTP_PROXY")
                        .withValue(PROXY_URL)
                        .endEnv()
                        .addNewEnv()
                        .withName("http_proxy")
                        .withValue(PROXY_URL)
                        .endEnv()
                        .withNewSecurityContext()
                        .withAllowPrivilegeEscalation(false)
                        .withNewCapabilities()
                        .withDrop("ALL")
                        .endCapabilities()
                        .endSecurityContext()
                        .endContainer()
                        .endSpec()
                        .build())
                .create();
        cleanups.add(() -> kube().pods().inNamespace(NAMESPACE).withName(name).delete());
        kube().pods().inNamespace(NAMESPACE).withName(name).waitUntilReady(60, TimeUnit.SECONDS);
        assertThat(kube().pods()
                        .inNamespace(NAMESPACE)
                        .withName(name)
                        .get()
                        .getStatus()
                        .getPodIP())
                .isEqualTo(ip);
        return name;
    }

    // ---- 配置 ----

    private static Path writeConfig() {
        try {
            Path directory = Files.createTempDirectory("egress-proxy-kind-it");
            Files.writeString(directory.resolve("agent.yaml"), """
                    kind: agent_version
                    agent_id: agt_egress_kind
                    agent_version_id: %s
                    name: egress
                    model:
                      model_id: mdl_unused
                    system_prompt: 出网测试
                    tools: []
                    mcp_servers: []
                    skills: []
                    subagents: []
                    permissions:
                      mode: default
                      rules: []
                    compaction:
                      reserved_tokens: 20000
                    plan_mode: false
                    knowledge:
                      knowledge_base_ids: []
                      prefetch: false
                      prefetch_top_k: 5
                    max_iters: 10
                    expose_thinking: false
                    """.formatted(AGENT_VERSION));
            Files.writeString(directory.resolve("environment-allowed.yaml"), environment(ENV_ALLOWED, ALLOWED));
            Files.writeString(directory.resolve("environment-other.yaml"), environment(ENV_OTHER, DENIED));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String environment(String environmentId, String allowed) {
        return """
                kind: environment
                environment_id: %s
                revision: 1
                tier: sandbox
                size: small
                egress_allowlist: [%s]
                """.formatted(environmentId, allowed);
    }
}
