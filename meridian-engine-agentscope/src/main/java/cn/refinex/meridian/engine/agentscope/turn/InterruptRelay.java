package cn.refinex.meridian.engine.agentscope.turn;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 一次 AgentScope 调用的中断。AgentScope 只在调用被受理（进入 {@code runningCalls}）之后响应
 * {@code interrupt(userId, sessionId)}，之前的中断被静默丢弃；调用在发出 {@code AGENT_START} 之后才被受理，所以受理前收到的
 * 中断，在看到第一个其他事件时再发一次。主线程的一轮与每次子智能体执行各有一个。
 *
 * <p>线程安全：方法互斥。
 */
final class InterruptRelay {

    private final HarnessAgent agent;
    private final String userId;
    private final String threadId;
    private boolean admitted;
    private boolean interruptBeforeAdmission;

    /**
     * 创建中断转发。
     *
     * @param agent 执行调用的智能体实例
     * @param userId AgentScope 的 {@code userId}
     * @param threadId 线程 ID，即 AgentScope 的 {@code sessionId}
     */
    InterruptRelay(HarnessAgent agent, String userId, String threadId) {
        this.agent = agent;
        this.userId = userId;
        this.threadId = threadId;
    }

    /** 以用户来源中断调用；调用尚未被受理时在受理后补发。 */
    synchronized void interrupt() {
        agent.interrupt(userId, threadId);
        if (!admitted) {
            interruptBeforeAdmission = true;
        }
    }

    /**
     * 观察调用的事件：第一个不是 {@code AGENT_START} 的事件说明调用已被受理，补发此前收到的中断。
     *
     * @param event AgentScope 事件
     */
    synchronized void observe(AgentEvent event) {
        if (admitted || event instanceof AgentStartEvent) {
            return;
        }
        admitted = true;
        if (interruptBeforeAdmission) {
            agent.interrupt(userId, threadId);
        }
    }
}
