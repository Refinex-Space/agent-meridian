package cn.refinex.meridian.engine.agentscope.testkit.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 脚本化的会话沙箱替身：记录收到的每条命令，按处理函数给出结果，不执行任何命令。用于内核层的 {@code sandbox} 档场景测试
 * （规则、工具结果、审计、外部沙箱的注入）；真实的命令执行与文件读写由 kind 集成测试验证。
 *
 * <p>与 AgentScope 2.0.4 的 {@code KubernetesSandbox} 一致：非零退出码以 {@link SandboxException.ExecException} 抛出。
 */
public final class FakeSandbox implements Sandbox {

    private final FakeState state = new FakeState();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicInteger starts = new AtomicInteger();
    private volatile Function<String, ExecResult> handler = command -> new ExecResult(0, "", "", false);

    /** 创建替身，工作区根为 {@code /workspace}。 */
    public FakeSandbox() {
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/workspace");
        state.setWorkspaceSpec(workspace);
        state.setSessionId("fake-sandbox");
    }

    /**
     * 设置命令的处理函数。
     *
     * @param handler 命令原文 → 结果
     * @return 本替身
     */
    public FakeSandbox respond(Function<String, ExecResult> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * 收到的命令原文，按顺序。
     *
     * @return 命令
     */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /**
     * {@code start()} 被调用的次数。
     *
     * @return 次数
     */
    public int starts() {
        return starts.get();
    }

    @Override
    public void start() {
        starts.incrementAndGet();
    }

    @Override
    public void stop() {
        throw new IllegalStateException("外部沙箱不应被 harness 停止");
    }

    @Override
    public void close() {
        throw new IllegalStateException("外部沙箱不应被 harness 关闭");
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        commands.add(command);
        ExecResult result = handler.apply(command);
        if (!result.ok()) {
            throw new SandboxException.ExecException(result.exitCode(), result.stdout(), result.stderr());
        }
        return result;
    }

    @Override
    public InputStream persistWorkspace() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void hydrateWorkspace(InputStream archive) {
        throw new UnsupportedOperationException("脚本化的沙箱不展开归档");
    }

    /** 替身的状态。 */
    private static final class FakeState extends SandboxState {}
}
