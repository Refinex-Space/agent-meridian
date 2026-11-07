package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 只用于装配的沙箱文件系统规范替身：能让 {@code HarnessAgent} 以 {@code sandbox} 档装配成功，但不能真正创建沙箱。
 * 生产的 {@code KubernetesFilesystemSpec} 与会话沙箱由 T11、T12a 提供并验证。
 *
 * <p>见架构设计「测试策略」「文件系统装配」。
 */
public final class FakeSandboxFilesystemSpec extends SandboxFilesystemSpec {

    @Override
    protected SandboxClient<?> createClient() {
        return new UnsupportedSandboxClient();
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return new FakeOptions();
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return new NoopSnapshotSpec();
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return new WorkspaceSpec();
    }

    /** 替身的客户端选项。 */
    private static final class FakeOptions extends SandboxClientOptions {

        @Override
        public String getType() {
            return "fake";
        }

        @Override
        public SandboxClient<? extends SandboxClientOptions> createClient() {
            return new UnsupportedSandboxClient();
        }
    }

    /** 所有操作都失败的客户端：装配测试不会真正使用沙箱。 */
    private static final class UnsupportedSandboxClient implements SandboxClient<FakeOptions> {

        @Override
        public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, FakeOptions options) {
            throw new UnsupportedOperationException("装配测试中的沙箱替身不能创建沙箱");
        }

        @Override
        public Sandbox resume(SandboxState state) {
            throw new UnsupportedOperationException("装配测试中的沙箱替身不能恢复沙箱");
        }

        @Override
        public void delete(Sandbox sandbox) {
            throw new UnsupportedOperationException("装配测试中的沙箱替身不能删除沙箱");
        }

        @Override
        public String serializeState(SandboxState state) {
            throw new UnsupportedOperationException("装配测试中的沙箱替身没有状态");
        }

        @Override
        public SandboxState deserializeState(String json) {
            throw new UnsupportedOperationException("装配测试中的沙箱替身没有状态");
        }
    }
}
