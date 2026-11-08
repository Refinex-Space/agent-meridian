package cn.refinex.meridian.engine.agentscope.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link SingleStartSandbox}：并发 {@code start()} 只执行一次（T11-A06），失败可重试，其余方法委托。 */
class SingleStartSandboxTest {

    @Test
    void concurrentStartRunsDelegateOnce() throws Exception {
        CountingSandbox delegate = new CountingSandbox();
        SingleStartSandbox sandbox = new SingleStartSandbox(delegate);
        int callers = 32;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    sandbox.start();
                    // 每个调用返回时，启动都已真正完成
                    assertThat(delegate.completed.get()).isTrue();
                    return null;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(delegate.starts.get()).isEqualTo(1);
        assertThat(delegate.maxConcurrentStarts.get()).isEqualTo(1);
    }

    @Test
    void failedStartIsRetriedByNextCall() throws Exception {
        CountingSandbox delegate = new CountingSandbox();
        delegate.failuresLeft.set(1);
        SingleStartSandbox sandbox = new SingleStartSandbox(delegate);

        assertThatThrownBy(sandbox::start).hasMessage("start failed");
        sandbox.start();
        sandbox.start();

        assertThat(delegate.starts.get()).isEqualTo(2);
    }

    @Test
    void startRunsAgainAfterStop() throws Exception {
        CountingSandbox delegate = new CountingSandbox();
        SingleStartSandbox sandbox = new SingleStartSandbox(delegate);

        sandbox.start();
        sandbox.stop();
        sandbox.start();

        assertThat(delegate.starts.get()).isEqualTo(2);
        assertThat(delegate.stops.get()).isEqualTo(1);
    }

    @Test
    void delegatesExecAndFileTransfer() throws Exception {
        CountingSandbox delegate = new CountingSandbox();
        SingleStartSandbox sandbox = new SingleStartSandbox(delegate);

        assertThat(sandbox.exec(null, "echo hi", 5).stdout()).isEqualTo("ran: echo hi");
        assertThat(sandbox.supportsFileTransfer("/workspace/a.txt")).isTrue();
        sandbox.uploadFile("/workspace/a.txt", new byte[] {1, 2});
        assertThat(sandbox.downloadFile("/workspace/a.txt")).containsExactly(1, 2);
        assertThat(sandbox.persistWorkspace().readAllBytes()).containsExactly(9);
        assertThat(sandbox.delegate()).isSameAs(delegate);
    }

    @Test
    void reportsNoFileTransferWhenDelegateLacksIt() {
        Sandbox plain = new PlainSandbox();
        SingleStartSandbox sandbox = new SingleStartSandbox(plain);

        assertThat(sandbox.supportsFileTransfer("/workspace/a.txt")).isFalse();
        assertThatThrownBy(() -> sandbox.downloadFile("/workspace/a.txt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 记录启动次数与并发度的沙箱；启动耗时 50 毫秒，让并发调用真正重叠。 */
    private static final class CountingSandbox extends PlainSandbox implements SandboxFileTransfer {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxConcurrentStarts = new AtomicInteger();
        final AtomicInteger failuresLeft = new AtomicInteger();
        final AtomicBoolean completed = new AtomicBoolean();
        private byte[] file = new byte[0];

        @Override
        public void start() throws Exception {
            starts.incrementAndGet();
            maxConcurrentStarts.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
                if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IllegalStateException("start failed");
                }
                completed.set(true);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }

        @Override
        public boolean supportsFileTransfer(String path) {
            return true;
        }

        @Override
        public void uploadFile(String path, byte[] content) {
            file = content.clone();
        }

        @Override
        public byte[] downloadFile(String path) {
            return file.clone();
        }
    }

    /** 不支持文件接口的沙箱。 */
    private static class PlainSandbox implements Sandbox {

        @Override
        public void start() throws Exception {}

        @Override
        public void stop() throws Exception {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, "ran: " + command, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(new byte[] {9});
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }
}
