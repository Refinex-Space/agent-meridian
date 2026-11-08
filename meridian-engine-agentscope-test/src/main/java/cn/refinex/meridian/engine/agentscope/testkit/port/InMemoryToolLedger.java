package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * {@link ToolLedgerPort} 的内存实现：记录每次工具执行的开始信息与最终状态。
 *
 * <p>线程安全：用并发映射保存，记录 ID 由 {@code Ids} 生成。
 *
 * <p>见架构设计「端口」「崩溃恢复」。
 */
public final class InMemoryToolLedger implements ToolLedgerPort {

    private final Map<String, Invocation> invocations = new ConcurrentHashMap<>();
    private final List<SandboxCommand> commands = new CopyOnWriteArrayList<>();

    @Override
    public Mono<String> recordStart(InvocationStart start) {
        return Mono.fromSupplier(() -> {
            String id = Ids.newId(IdPrefix.TOOL_INVOCATION);
            invocations.put(id, new Invocation(id, start, Status.RUNNING, null));
            return id;
        });
    }

    @Override
    public Mono<Void> recordFinish(String invocationId, Status status, BlobRef resultRef) {
        return Mono.fromRunnable(() -> {
            if (status == Status.RUNNING) {
                throw new IllegalArgumentException("结束状态不能是 RUNNING");
            }
            Invocation previous = invocations.get(invocationId);
            if (previous == null) {
                throw new NotFoundException("工具调用记录不存在：" + invocationId);
            }
            invocations.put(invocationId, new Invocation(invocationId, previous.start(), status, resultRef));
        });
    }

    @Override
    public Mono<Void> recordSandboxCommand(SandboxCommand command) {
        return Mono.fromRunnable(() -> commands.add(command));
    }

    /**
     * 记录过的沙箱命令，按记录顺序。
     *
     * @return 沙箱命令
     */
    public List<SandboxCommand> sandboxCommands() {
        return List.copyOf(commands);
    }

    /**
     * 全部记录，顺序不保证。
     *
     * @return 记录
     */
    public List<Invocation> invocations() {
        return List.copyOf(invocations.values());
    }

    /**
     * 一条账本记录。
     *
     * @param id 记录 ID
     * @param start 开始信息
     * @param status 当前状态
     * @param resultRef 结果引用；可为空
     */
    public record Invocation(String id, InvocationStart start, Status status, BlobRef resultRef) {}
}
