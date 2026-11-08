package cn.refinex.meridian.engine.agentscope.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 包装 AgentScope 的 {@link Sandbox}，保证 {@link #start()} 只真正执行一次且线程安全，见架构设计「会话沙箱」"启动的并发"。
 *
 * <p>harness 每次调用都会对外部沙箱执行 {@code start()}，而 {@code AbstractBaseSandbox.start()} 不是线程安全的（探测工作区、
 * 恢复快照、写投影），并行的子智能体会同时触发。第一次成功之后的调用直接返回；正在启动时其他调用等待它结束；启动失败不记为
 * 已启动，下一次调用重新执行。{@link #stop()} 之后再调用 {@code start()} 会重新执行。
 *
 * <p>其余方法原样委托；被包装的沙箱支持文件接口（{@link SandboxFileTransfer}）时同样委托，否则 harness 退回 tar 方式传输。
 */
public final class SingleStartSandbox implements Sandbox, SandboxFileTransfer {

    private final Sandbox delegate;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean started;

    /**
     * 包装沙箱。
     *
     * @param delegate 被包装的沙箱
     */
    public SingleStartSandbox(Sandbox delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 返回被包装的沙箱。
     *
     * @return AgentScope 沙箱
     */
    public Sandbox delegate() {
        return delegate;
    }

    @Override
    public void start() throws Exception {
        if (started) {
            return;
        }
        lock.lock();
        try {
            if (!started) {
                delegate.start();
                started = true;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() throws Exception {
        lock.lock();
        try {
            started = false;
            delegate.stop();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() throws Exception {
        delegate.shutdown();
    }

    @Override
    public void close() throws Exception {
        lock.lock();
        try {
            started = false;
            delegate.close();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public SandboxState getState() {
        return delegate.getState();
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) throws Exception {
        return delegate.exec(runtimeContext, command, timeoutSeconds);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return delegate.persistWorkspace();
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        delegate.hydrateWorkspace(archive);
    }

    @Override
    public boolean supportsFileTransfer(String path) {
        return delegate instanceof SandboxFileTransfer transfer && transfer.supportsFileTransfer(path);
    }

    @Override
    public void uploadFile(String path, byte[] content) throws Exception {
        transfer().uploadFile(path, content);
    }

    @Override
    public byte[] downloadFile(String path) throws Exception {
        return transfer().downloadFile(path);
    }

    private SandboxFileTransfer transfer() {
        if (delegate instanceof SandboxFileTransfer transfer) {
            return transfer;
        }
        throw new UnsupportedOperationException(
                "沙箱不支持文件接口：" + delegate.getClass().getName());
    }
}
