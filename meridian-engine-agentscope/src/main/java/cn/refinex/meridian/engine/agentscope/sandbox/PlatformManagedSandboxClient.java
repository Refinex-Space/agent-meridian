package cn.refinex.meridian.engine.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 装配进 {@code KubernetesFilesystemSpec} 的沙箱客户端：一律拒绝，保证 harness 不会自己创建或恢复沙箱。
 *
 * <p>会话沙箱由平台管理，每轮作为外部沙箱（{@code SandboxContext.externalSandbox}）放进 {@code RuntimeContext}，2.0.4 的
 * {@code SandboxManager} 对外部沙箱不使用客户端。调用里缺少外部沙箱时，harness 会退回到装配时的默认上下文，用客户端认领一个
 * 新沙箱（不打会话标签、不落库、每次调用后删除）；用本客户端时这种情况以异常结束，而不是悄悄多出沙箱。
 */
public final class PlatformManagedSandboxClient implements SandboxClient<SandboxClientOptions> {

    /** 唯一实例，无状态。 */
    public static final PlatformManagedSandboxClient INSTANCE = new PlatformManagedSandboxClient();

    private static final String MESSAGE = "会话沙箱由平台管理，harness 不能自行创建或恢复沙箱：调用缺少外部沙箱";

    private PlatformManagedSandboxClient() {}

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, SandboxClientOptions options) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public void delete(Sandbox sandbox) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public String serializeState(SandboxState state) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public SandboxState deserializeState(String json) {
        throw new IllegalStateException(MESSAGE);
    }
}
