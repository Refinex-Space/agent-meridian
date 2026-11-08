package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.turn.ToolRetryContext;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 所有平台工具的基类（架构设计「平台工具」：平台工具必须继承 {@code ToolBase} 并实现 {@code matchRule}）。
 *
 * <p>子类只声明名称、描述、入参 schema、副作用等级与规则内容语法（{@link Spec}），并实现 {@link #execute}。基类负责：
 *
 * <ul>
 *   <li>规则匹配：规则内容为空时匹配全部调用（与 {@code PermissionEngine} 的调用方式一致），否则交给 {@link RuleSyntax}；
 *       "记住此决定"的建议规则带上内容（登记事项 D21）。
 *   <li>工具账本：执行前经 {@link ToolLedgerPort} 记录开始（{@code running}，幂等键 {@code session_id:tool_use_id}，恢复确认
 *       重试时用 {@link ToolRetryContext} 中的原键），执行后记录成功或失败；{@code non_idempotent} 工具的结果写入对象存储，
 *       结果引用进入账本（登记事项 D14），恢复说明据此给出结果摘要。账本写入受租约约束，被拒时以 {@code LeaseLostException}
 *       结束调用、不执行工具。指标 {@code meridian_tool_calls_total} 与审计记录由账本的实现在记录结束时写出，所以每个平台工具
 *       的调用都有且只有一条。
 *   <li>文件系统：经 {@code HarnessAgent.getWorkspaceManager().getFilesystem()} 取得（装配后由 {@link #attach} 绑定），调用时
 *       原样传入 {@code ToolCallParam.getRuntimeContext()}。
 * </ul>
 *
 * <p>线程安全：装配完成后不可变；同一实例被共享该智能体实例的所有会话并发调用，子类不能保存调用相关的状态。
 */
public abstract class PlatformTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(PlatformTool.class);

    private final SideEffect sideEffect;
    private final RuleSyntax ruleSyntax;
    private final ToolLedgerPort ledger;
    private final BlobStorePort blobs;
    private volatile HarnessAgent agent;

    /**
     * 创建平台工具。
     *
     * @param spec 工具声明
     * @param services 工具共用的平台服务
     */
    protected PlatformTool(Spec spec, ToolServices services) {
        super(ToolBase.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .readOnly(spec.sideEffect() == SideEffect.NONE)
                .concurrencySafe(spec.concurrencySafe()));
        this.sideEffect = spec.sideEffect();
        this.ruleSyntax = spec.ruleSyntax();
        this.ledger = services.ledger();
        this.blobs = services.blobs();
    }

    /**
     * 副作用等级，决定崩溃恢复时能否重放（架构设计「崩溃恢复」）。
     *
     * @return 副作用等级
     */
    public final SideEffect sideEffect() {
        return sideEffect;
    }

    /**
     * 绑定所属的智能体实例，之后经它的工作区管理器取文件系统。由装配器在 {@code build()} 之后调用一次。
     *
     * @param owner 智能体实例
     * @throws IllegalStateException 已经绑定到另一个实例时
     */
    public final void attach(HarnessAgent owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (this) {
            if (agent != null && agent != owner) {
                throw new IllegalStateException("平台工具 " + getName() + " 已绑定到另一个智能体实例");
            }
            agent = owner;
        }
    }

    @Override
    public final boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null || ruleContent.isEmpty()) {
            return true;
        }
        return ruleSyntax.matches(ruleContent, toolInput == null ? Map.of() : toolInput);
    }

    @Override
    public final List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        Optional<String> content = ruleSyntax.suggest(toolInput == null ? Map.of() : toolInput);
        return content.map(c -> List.of(new PermissionRule(getName(), c, PermissionBehavior.ALLOW, "suggested")))
                .orElseGet(() -> super.generateSuggestions(toolInput));
    }

    @Override
    public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext runtime = param.getRuntimeContext();
        PlatformContext platform = runtime == null ? null : runtime.get(PlatformContext.class);
        if (platform == null || platform.sessionId() == null || platform.threadId() == null) {
            return Mono.error(new IllegalStateException("平台工具必须在带 PlatformContext 的轮次中调用：" + getName()));
        }
        HarnessAgent owner = agent;
        if (owner == null) {
            return Mono.error(new IllegalStateException("平台工具还没有绑定智能体实例：" + getName()));
        }
        String toolUseId = param.getToolUseBlock().getId();
        ToolRetryContext retry = runtime.get(ToolRetryContext.class);
        String idempotencyKey = retry != null ? retry.idempotencyKey() : platform.sessionId() + ":" + toolUseId;
        Map<String, Object> input = param.getInput();
        ToolCall call = new ToolCall(
                toolUseId,
                input,
                runtime,
                platform,
                idempotencyKey,
                owner.getWorkspaceManager().getFilesystem());
        InvocationStart start =
                new InvocationStart(platform.threadId(), toolUseId, getName(), sideEffect, idempotencyKey, input);
        return ledger.recordStart(start)
                .flatMap(invocationId -> run(call)
                        .flatMap(output -> finish(invocationId, output).thenReturn(output.toBlock()))
                        .doOnCancel(() -> interrupted(invocationId, platform)))
                .contextWrite(PlatformContexts.write(platform));
    }

    /**
     * 执行工具。在有界弹性线程上调用，可以阻塞（文件系统是同步接口）；不要自己吞掉 {@code LeaseLostException}。
     *
     * @param call 本次调用
     * @return 结果；参数不对、目标不存在等可预期的问题以 {@link ToolOutput#error} 返回或抛 {@link ToolInputException}，让模型修正
     * @throws Exception 意外错误，基类记录日志并转成出错的结果（只给模型看异常的消息）
     */
    protected abstract ToolOutput execute(ToolCall call) throws Exception;

    private Mono<ToolOutput> run(ToolCall call) {
        return Mono.fromCallable(() -> execute(call))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ToolInputException.class, error -> Mono.just(ToolOutput.error(error.getMessage())))
                .onErrorResume(error -> !(error instanceof LeaseLostException), error -> {
                    log.warn("平台工具执行出错 tool={} toolUseId={}", getName(), call.toolUseId(), error);
                    String reason =
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    return Mono.just(ToolOutput.error(reason));
                });
    }

    /** 记录结束：non_idempotent 工具的结果先写对象存储，引用进入账本。 */
    private Mono<Void> finish(String invocationId, ToolOutput output) {
        Status status = output.error() ? Status.FAILED : Status.SUCCEEDED;
        if (sideEffect != SideEffect.NON_IDEMPOTENT) {
            return ledger.recordFinish(invocationId, status, null);
        }
        Mono<BlobRef> ref = blobs.put("text/plain; charset=utf-8", output.text().getBytes(StandardCharsets.UTF_8));
        return ref.flatMap(r -> ledger.recordFinish(invocationId, status, r));
    }

    /** 调用被取消（用户中断或失去租约）：尽力把账本记为 interrupted；租约已失去时写入会被拒绝，只记日志。 */
    private void interrupted(String invocationId, PlatformContext platform) {
        ledger.recordFinish(invocationId, Status.INTERRUPTED, null)
                .contextWrite(PlatformContexts.write(platform))
                .subscribe(ignored -> {}, error -> log.debug("工具调用被取消，账本未能记为 interrupted id={}", invocationId, error));
    }

    /**
     * 平台工具的声明。
     *
     * @param name 工具名，取自 {@code PlatformToolNames}；必填
     * @param description 给模型看的英文描述：用途、何时使用、参数、返回格式、限制；必填
     * @param inputSchema 入参的 JSON Schema；必填
     * @param sideEffect 副作用等级；必填
     * @param ruleSyntax 规则内容语法；必填
     * @param concurrencySafe 同一批中能否与其他调用并行执行
     */
    public record Spec(
            String name,
            String description,
            Map<String, Object> inputSchema,
            SideEffect sideEffect,
            RuleSyntax ruleSyntax,
            boolean concurrencySafe) {

        /**
         * 创建声明。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Spec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(inputSchema, "inputSchema");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(ruleSyntax, "ruleSyntax");
        }
    }

    /**
     * 平台工具共用的服务，由 runtime 实现的端口。
     *
     * @param ledger 工具账本；必填
     * @param blobs 大对象存储，保存 {@code non_idempotent} 工具的结果；必填
     */
    public record ToolServices(ToolLedgerPort ledger, BlobStorePort blobs) {

        /**
         * 创建服务集合。
         *
         * @throws NullPointerException 缺少端口时
         */
        public ToolServices {
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(blobs, "blobs");
        }
    }
}
