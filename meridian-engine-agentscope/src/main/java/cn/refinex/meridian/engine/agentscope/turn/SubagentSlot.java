package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.engine.agentscope.middleware.SubagentQuota;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * 主线程一轮中拉起子智能体的入口，由引擎每轮以类型键放进主线程的 {@code RuntimeContext}，{@code spawn_subagent} 从调用的上下文中
 * 取出它（架构设计「子智能体」）。
 *
 * <p>它记下父线程这一轮的上下文（权限策略、身份、会话沙箱）与本轮登记的子智能体执行：{@code AgentEngine.interrupt} 先经
 * {@link #interruptAll} 中断它们，再中断父线程——{@code spawn_subagent} 本身是一个工具，父线程被中断后它不会停。父线程被中断之后
 * 才开始的拉起直接拒绝，登记时父线程已被中断的执行在登记时中断。
 *
 * <p>线程安全：登记与中断互斥。
 */
public final class SubagentSlot {

    private final SubagentRunner runner;
    private final Parent parent;
    private final SubagentQuota quota;
    private final Set<InterruptRelay> running = new LinkedHashSet<>();
    private boolean interrupted;

    /**
     * 创建本轮的入口。
     *
     * @param runner 执行子智能体的组件
     * @param parent 父线程这一轮的上下文
     * @param quota 本轮的拉起名额，与放进 {@code RuntimeContext} 供 {@code PolicyMiddleware} 登记的是同一个
     */
    SubagentSlot(SubagentRunner runner, Parent parent, SubagentQuota quota) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.quota = Objects.requireNonNull(quota, "quota");
    }

    /**
     * 从调用上下文读取本轮的入口。
     *
     * @param ctx 调用上下文；可为空
     * @return 入口；不是经平台引擎执行的主线程调用（子线程、没有配置子智能体执行的内核）时为空
     */
    public static SubagentSlot of(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(SubagentSlot.class);
    }

    /**
     * 拉起一个子智能体并同步等待它结束。本轮已达拉起上限或父线程已被中断时不创建子线程。
     *
     * @param subagent 子智能体配置，属于父智能体版本
     * @param task 交给子智能体的任务描述
     * @param parentToolUseId 本次 {@code spawn_subagent} 调用的 ID
     * @return 结果
     */
    public Mono<SubagentOutcome> spawn(SubagentSpec subagent, String task, String parentToolUseId) {
        return Mono.defer(() -> {
            if (quota.rejected(parentToolUseId)) {
                return Mono.<SubagentOutcome>just(
                        new SubagentOutcome.Refused(SubagentOutcome.RefusalReason.LIMIT_REACHED));
            }
            if (isInterrupted()) {
                return Mono.<SubagentOutcome>just(
                        new SubagentOutcome.Refused(SubagentOutcome.RefusalReason.INTERRUPTED));
            }
            return runner.run(this, subagent, task, parentToolUseId);
        });
    }

    /** 父线程这一轮的上下文。 */
    Parent parent() {
        return parent;
    }

    /** 中断本轮登记的全部子智能体执行，之后登记的执行在登记时中断。 */
    void interruptAll() {
        List<InterruptRelay> targets;
        synchronized (this) {
            interrupted = true;
            targets = new ArrayList<>(running);
        }
        targets.forEach(InterruptRelay::interrupt);
    }

    /** 登记一次子智能体执行；父线程已被中断时立即中断它。 */
    void register(InterruptRelay relay) {
        boolean interruptNow;
        synchronized (this) {
            running.add(relay);
            interruptNow = interrupted;
        }
        if (interruptNow) {
            relay.interrupt();
        }
    }

    /** 取消登记，子智能体执行结束（含异常、取消）时调用。 */
    synchronized void unregister(InterruptRelay relay) {
        running.remove(relay);
    }

    private synchronized boolean isInterrupted() {
        return interrupted;
    }

    /**
     * 父线程这一轮的上下文。
     *
     * @param turn 本轮的上下文：平台上下文、终端用户、预先合成的子智能体权限策略；必填
     * @param agent 父智能体版本配置；必填
     * @param environment 环境配置；必填
     * @param userId AgentScope 的 {@code userId}，子线程沿用；必填
     * @param sandbox 会话沙箱；{@code none} 档为空
     */
    record Parent(
            TurnContext turn, ResolvedAgentSpec agent, EnvironmentSpec environment, String userId, Sandbox sandbox) {

        Parent {
            Objects.requireNonNull(turn, "turn");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(userId, "userId");
        }
    }
}
