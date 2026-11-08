package cn.refinex.meridian.engine.agentscope.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemorySnapshotStore;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/** {@link PlatformSnapshotClient}：快照键格式，经端口上传、下载与判断存在。 */
class PlatformSnapshotClientTest {

    private final InMemorySnapshotStore store = new InMemorySnapshotStore();
    private final PlatformSnapshotClient client = new PlatformSnapshotClient(store);

    @Test
    void keyFollowsArchitecturePath() {
        PlatformContext context = PlatformContext.builder()
                .tenantId("ten_1")
                .projectId("prj_1")
                .sessionId("ses_1")
                .build();

        assertThat(PlatformSnapshotClient.key(context, "01JX")).isEqualTo("rt/snapshots/ten_1/prj_1/ses_1/01JX.tar");
    }

    @Test
    void keyRequiresSessionInContext() {
        PlatformContext context =
                PlatformContext.builder().tenantId("ten_1").projectId("prj_1").build();

        assertThatThrownBy(() -> PlatformSnapshotClient.key(context, "01JX")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteSnapshotRoundTripsThroughPort() throws Exception {
        String key = "rt/snapshots/ten_1/prj_1/ses_1/01JX.tar";
        RemoteSandboxSnapshot snapshot = client.at(key);

        assertThat(snapshot.isRestorable()).isFalse();
        snapshot.persist(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThat(snapshot.isRestorable()).isTrue();
        assertThat(snapshot.getId()).isEqualTo(key);
        assertThat(snapshot.restore().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void missingSnapshotFailsRestore() {
        RemoteSandboxSnapshot snapshot = client.at("rt/snapshots/ten_1/prj_1/ses_1/missing.tar");

        assertThatThrownBy(snapshot::restore).isInstanceOf(SandboxException.SnapshotException.class);
    }
}
