package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 记账的测试工具：像平台工具（T10a）一样，发起副作用之前经 {@link ToolLedgerPort} 记录开始，执行完把结果写入对象存储并记录
 * 结束（结果引用进入账本）；可以在执行中途
 * 卡住，供测试在"工具正在执行"时强杀、接管租约或下线。每个节点一个实例（账本带节点的租约条件），同一工具在各节点的调用记录
 * 共用一个 {@link Calls}，用于断言不重复执行。
 */
final class LedgerTool extends ToolBase {

    private final SideEffect sideEffect;
    private final ToolLedgerPort ledger;
    private final BlobStorePort blobs;
    private final Calls calls;

    LedgerTool(String name, SideEffect sideEffect, ToolLedgerPort ledger, BlobStorePort blobs, Calls calls) {
        super(ToolBase.builder()
                .name(name)
                .description("记账的测试工具 " + name)
                .inputSchema(RecordingTool.TEXT_SCHEMA)
                .readOnly(sideEffect == SideEffect.NONE)
                .concurrencySafe(true));
        this.sideEffect = sideEffect;
        this.ledger = ledger;
        this.blobs = blobs;
        this.calls = calls;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        PlatformContext platform = param.getRuntimeContext().get(PlatformContext.class);
        String toolUseId = param.getToolUseBlock().getId();
        InvocationStart start = new InvocationStart(
                platform.threadId(),
                toolUseId,
                getName(),
                sideEffect,
                sideEffect == SideEffect.NON_IDEMPOTENT ? "idem-" + toolUseId : null,
                param.getInput());
        return ledger.recordStart(start)
                .flatMap(invocationId -> Mono.fromCallable(() -> calls.execute(getName(), toolUseId, param))
                        .subscribeOn(Schedulers.boundedElastic())
                        // 结果写入对象存储并记进账本：强杀发生在结果事件写库之前时，恢复说明从账本取结果摘要
                        .flatMap(text -> blobs.put("text/plain", text.getBytes(StandardCharsets.UTF_8))
                                .flatMap(ref -> ledger.recordFinish(invocationId, Status.SUCCEEDED, ref))
                                .thenReturn(ToolResultBlock.text(text))))
                .contextWrite(PlatformContexts.write(platform));
    }

    /** 跨节点共用的调用记录与闸门：闸门关闭时工具执行到一半卡住，测试放行后才返回。 */
    static final class Calls {

        private final List<String> toolUseIds = new CopyOnWriteArrayList<>();
        private final List<CountDownLatch> releases = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(0);

        /** 关上闸门：之后的调用卡在执行中，直到 {@link #open()}。 */
        void close() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            releases.add(release);
        }

        /** 打开闸门，放行所有卡住的调用（包括更早一次关闭时卡住的）。 */
        void open() {
            releases.forEach(CountDownLatch::countDown);
        }

        /** 等待有调用进入执行。 */
        void awaitEntered() throws InterruptedException {
            if (!entered.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待工具开始执行超时");
            }
        }

        List<String> toolUseIds() {
            return List.copyOf(toolUseIds);
        }

        String execute(String name, String toolUseId, ToolCallParam param) throws InterruptedException {
            toolUseIds.add(toolUseId);
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("工具一直没有被放行");
            }
            return name + ":" + param.getInput().get("text");
        }
    }
}
