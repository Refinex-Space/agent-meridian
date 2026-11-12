package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriver;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxDriverSettings;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.retention.app.RetentionProperties;
import cn.refinex.meridian.runtime.retention.app.SessionPurger;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository.DeletedSession;
import cn.refinex.meridian.runtime.sandbox.app.ObjectStoreSnapshotStore;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxObjectKeys;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.runtime.webhook.app.WebhookDispatcher;
import cn.refinex.meridian.runtime.webhook.app.WebhookProperties;
import cn.refinex.meridian.runtime.webhook.infra.system.WebhookQueueRepository;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 删除会话后 kind 集群中没有残留（T17a-A02 的沙箱部分，ADR-0049；登记事项 D37）：会话沙箱、产物与快照，以及带会话标签但没有记进会话
 * 的沙箱（模拟认领之后、记进会话之前崩溃）都被清理作业删除。真实装配的 runtime 节点、{@code ScriptedModel}、kind 集群中的预热池。
 *
 * <p>前提同 {@link SandboxSessionKindIT}。运行：{@code ./mvnw -Pkind -pl meridian-runtime -am verify -Dit.test=SessionPurgeKindIT}。
 */
@Tag("kind")
class SessionPurgeKindIT {

    private static KubernetesClient kube;
    private static KubernetesSandboxDriver driver;
    private String sessionId;

    @BeforeAll
    static void connect() {
        kube = new KubernetesClientBuilder()
                .withConfig(Config.autoConfigure(SandboxSessionKindIT.KUBE_CONTEXT))
                .build();
        driver = new KubernetesSandboxDriver(
                SandboxDriverSettings.defaults(new SandboxDriverSettings.PortForward()),
                kube,
                new ObjectStoreSnapshotStore(RuntimeTestObjectStore.get()));
    }

    @AfterAll
    static void disconnect() {
        driver.close();
        kube.close();
    }

    @AfterEach
    void cleanUp() {
        if (sessionId != null) {
            driver.deleteSessionSandboxes(sessionId).block(Duration.ofMinutes(2));
        }
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void purgingADeletedSessionRemovesItsSandboxesIncludingUntrackedOnes() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_bash", "bash", Map.of("command", "mkdir -p outputs && echo 季度报告 > outputs/report.txt")))
                .respond(r -> r.text("已生成"))
                .build();
        WorkerFixture fixture = new WorkerFixture(
                model,
                agent -> agent.tools(SandboxSessionKindIT.TOOLS.toArray(String[]::new))
                        .permissions(SandboxSessionKindIT.ALLOW_ALL));
        fixture.useSandbox(new WorkerNode.SandboxWiring(driver, driver, SandboxSessionKindIT.properties()));
        WorkerNode node = fixture.node("node-purge");
        TestSession session = fixture.session();
        sessionId = session.sessionId();

        fixture.send(node, session, "生成报告", cn.refinex.meridian.engine.spi.event.UserEvent.Delivery.QUEUE);
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        String sandboxId = (String) DbAssertions.row(
                        "SELECT sandbox_state ->> 'sandbox_id' AS sandbox_id FROM rt.sessions WHERE id = ?", sessionId)
                .get("sandbox_id");
        assertThat(sandboxId).isNotNull();
        String outputsPrefix = SandboxObjectKeys.outputsPrefix(session.tenantId(), session.projectId(), sessionId);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.sandbox_outputs WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(1L);
        // 认领之后、记进会话之前崩溃留下的沙箱：带会话标签，会话不知道它
        PlatformContexts.run(
                session.context(),
                () -> driver.create(new SandboxRequest(sessionId, SandboxSize.SMALL))
                        .block(Duration.ofMinutes(3)));
        assertThat(claims(sessionId)).hasSize(2);

        PlatformContexts.run(session.context(), () -> fixture.sessions(node).delete(sessionId));
        DataSource dataSource = RuntimeTestDatabase.dataSource();
        WebhookDispatcher webhooks = new WebhookDispatcher(
                new WebhookQueueRepository(dataSource),
                (tenantId, projectId) -> List.of(),
                (url, headers, body) -> {
                    throw new AssertionError("没有订阅，不应投递");
                },
                new WebhookProperties(
                        false,
                        Duration.ofSeconds(2),
                        100,
                        4,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofDays(30),
                        false,
                        List.of()),
                RuntimeTestDatabase.transactions(),
                new SimpleMeterRegistry(),
                fixture.clock);
        SessionPurger purger = new SessionPurger(
                new SessionLifecycleRepository(dataSource),
                node.sandboxes(),
                webhooks,
                RuntimeTestObjectStore.get(),
                new OutboxWriter(dataSource, "rt", fixture.clock),
                new RetentionProperties(
                        false,
                        Duration.ofSeconds(60),
                        20,
                        Duration.ofHours(12),
                        Duration.ofHours(1),
                        Duration.ofDays(90),
                        20,
                        Duration.ofHours(1),
                        100,
                        365),
                RuntimeTestDatabase.transactions(),
                fixture.clock);
        Instant deletedAt =
                DbAssertions.instant(DbAssertions.row("SELECT deleted_at FROM rt.sessions WHERE id = ?", sessionId)
                        .get("deleted_at"));
        DeletedSession deleted = new DeletedSession(sessionId, session.tenantId(), session.projectId(), deletedAt);
        // 删除通知还在 Webhook 的 outbox 里：扇出（没有订阅）之后才清理
        assertThat(purger.purge(deleted)).isFalse();
        webhooks.runOnce();
        assertThat(purger.purge(deleted)).isTrue();

        assertThat(claims(sessionId)).as("kind 集群中没有该会话的沙箱").isEmpty();
        assertThat(DbAssertions.list("SELECT count(*) FROM rt.sessions WHERE id = ?", Long.class, sessionId))
                .containsExactly(0L);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.sandbox_outputs WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(0L);
        assertThat(RuntimeTestObjectStore.get().deleteByPrefix(outputsPrefix)).isZero();
        assertThat(RuntimeTestObjectStore.get()
                        .deleteByPrefix(
                                SandboxObjectKeys.snapshotsPrefix(session.tenantId(), session.projectId(), sessionId)))
                .isZero();
    }

    private static List<SandboxClaim> claims(String sessionId) {
        return kube.resources(SandboxClaim.class)
                .inNamespace("meridian-sandbox")
                .withLabel(KubernetesSandboxDriver.SESSION_LABEL, sessionId)
                .list()
                .getItems();
    }
}
