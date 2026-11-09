package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.agentscope.turn.SubagentOutcome;
import cn.refinex.meridian.engine.agentscope.turn.SubagentSlot;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 平台工具 {@code spawn_subagent}：同步拉起智能体版本中声明的一个子智能体，把它的最终消息作为工具结果交给父智能体（架构设计
 * 「子智能体」，ADR-0012）。不使用 harness 的 {@code agent_spawn}：它自己生成子会话 ID、实例状态被所有会话共享、子智能体继承父
 * 智能体的全部中间件、审批没有出口。
 *
 * <ul>
 *   <li>入参 {@code subagent}（子智能体名，schema 中是枚举）与 {@code task}（任务描述）；规则内容是子智能体名。
 *   <li>副作用等级取子智能体可用工具中最高的等级，由 {@link PlatformToolProvider} 按父智能体实例中这些工具的等级算出。
 *   <li>执行经本轮的 {@link SubagentSlot}：创建子线程、执行、超时、中断与拉起上限都在那里；本工具只负责校验入参与把结果写成给模型
 *       看的文本。可以与同一批中的其他调用并行，每个子智能体有自己的子线程。
 * </ul>
 *
 * <p>线程安全：不保存调用相关的状态。
 */
public final class SpawnSubagentTool extends PlatformTool {

    private final ResolvedAgentSpec agent;

    /**
     * 创建工具。
     *
     * @param services 平台服务
     * @param agent 父智能体版本配置，必须声明了子智能体
     * @param sideEffect 子智能体可用工具中最高的副作用等级
     * @throws IllegalArgumentException 智能体版本没有声明子智能体时
     */
    public SpawnSubagentTool(ToolServices services, ResolvedAgentSpec agent, SideEffect sideEffect) {
        super(spec(agent, sideEffect), services);
        this.agent = agent;
    }

    private static Spec spec(ResolvedAgentSpec agent, SideEffect sideEffect) {
        if (agent.subagents().isEmpty()) {
            throw new IllegalArgumentException("智能体版本没有声明子智能体：" + agent.agentVersionId());
        }
        StringBuilder description = new StringBuilder("""
                Delegate a self-contained task to a subagent and wait for its final answer. The subagent starts \
                with an empty context: it sees only the task text, not this conversation, so include every fact, \
                file path and constraint it needs, and say what the answer should contain. It works with its own, \
                narrower set of tools in the same workspace, cannot ask for approval, and cannot spawn subagents. \
                Several subagents can run in parallel when you call this tool more than once in one response; give \
                each one its own output directory if they write files. The number of subagents per turn is limited.

                Returns the subagent's final message. If the subagent was stopped (timeout, interruption, a tool \
                call that needed approval, or an error), the result is an error that explains why.

                Available subagents:
                """);
        for (SubagentSpec subagent : agent.subagents()) {
            description
                    .append("- ")
                    .append(subagent.name())
                    .append(": ")
                    .append(subagent.description())
                    .append('\n');
        }
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "subagent",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                agent.subagents().stream()
                                        .map(SubagentSpec::name)
                                        .toList(),
                                "description",
                                "Name of the subagent to run."),
                        "task",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Complete, self-contained instructions for the subagent.")),
                "required",
                List.of("subagent", "task"));
        return new Spec(
                PlatformToolNames.SPAWN_SUBAGENT,
                description.toString().strip(),
                schema,
                sideEffect,
                RuleSyntax.resourceId("subagent"),
                true);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        throw new UnsupportedOperationException("spawn_subagent 经 executeAsync 执行");
    }

    @Override
    protected Mono<ToolOutput> executeAsync(ToolCall call) {
        String name = call.string("subagent");
        SubagentSpec subagent = name == null ? null : agent.subagent(name).orElse(null);
        if (subagent == null) {
            return Mono.just(ToolOutput.error("Unknown subagent '" + name + "'. Use one of: "
                    + String.join(
                            ", ",
                            agent.subagents().stream().map(SubagentSpec::name).toList()) + "."));
        }
        String task = call.string("task");
        if (task == null || task.isBlank()) {
            return Mono.just(ToolOutput.error("The task is empty. Describe the work for the subagent."));
        }
        SubagentSlot slot = SubagentSlot.of(call.runtimeContext());
        if (slot == null) {
            return Mono.just(ToolOutput.error("Subagents are not available in this execution."));
        }
        return slot.spawn(subagent, task, call.toolUseId()).map(outcome -> output(subagent, outcome));
    }

    /** 把子智能体的结果写成给模型看的文本：只有正常结束是成功的结果，其余都是说明原因的错误结果。 */
    static ToolOutput output(SubagentSpec subagent, SubagentOutcome outcome) {
        String name = subagent.name();
        return switch (outcome) {
            case SubagentOutcome.Finished finished
            when finished.stopReason() == StopReason.END_TURN ->
                ToolOutput.ok(
                        finished.text().isBlank()
                                ? "Subagent '" + name + "' finished without a final message."
                                : finished.text());
            case SubagentOutcome.Finished finished ->
                ToolOutput.error(stopped(name, finished) + partial(finished.text()));
            case SubagentOutcome.Rejected rejected ->
                ToolOutput.error("Subagent '" + name + "' stopped because "
                        + (rejected.toolNames().isEmpty()
                                ? "a tool call"
                                : "the tool call(s) " + String.join(", ", rejected.toolNames()))
                        + " needed approval, and subagents cannot ask for approval. The calls were rejected. "
                        + "Do the work yourself or choose another approach.");
            case SubagentOutcome.TimedOut timedOut ->
                ToolOutput.error("Subagent '" + name + "' did not finish within "
                        + timedOut.timeout().toSeconds() + " seconds and was cancelled.");
            case SubagentOutcome.Refused refused ->
                ToolOutput.error(
                        switch (refused.reason()) {
                            case LIMIT_REACHED ->
                                "The limit of subagents per turn has been reached; this call was rejected. "
                                        + "Continue with the results you already have.";
                            case INTERRUPTED -> "The turn was interrupted; subagent '" + name + "' was not started.";
                        });
        };
    }

    private static String stopped(String name, SubagentOutcome.Finished finished) {
        return switch (finished.stopReason()) {
            case INTERRUPTED -> "Subagent '" + name + "' was interrupted before it finished.";
            case MAX_TURNS -> "Subagent '" + name + "' stopped after reaching its maximum number of iterations.";
            case BUDGET_EXCEEDED -> "Subagent '" + name + "' stopped because the budget is exhausted.";
            case TOOLS_DENIED -> "Subagent '" + name + "' stopped because all of its tool calls were denied.";
            case ERROR, END_TURN ->
                "Subagent '" + name + "' failed" + (finished.error() == null ? "." : ": " + finished.error());
        };
    }

    private static String partial(String text) {
        return text.isBlank() ? "" : "\nPartial result:\n" + text;
    }
}
