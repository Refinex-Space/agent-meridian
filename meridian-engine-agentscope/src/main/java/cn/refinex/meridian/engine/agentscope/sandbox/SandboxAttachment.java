package cn.refinex.meridian.engine.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;

/**
 * 按序列化状态取得可以交给 harness 的会话沙箱（{@code SandboxContext.externalSandbox}），由 {@link KubernetesSandboxDriver}
 * 实现；测试用内存实现替换。见架构设计「会话沙箱」"获取"。
 */
public interface SandboxAttachment {

    /**
     * 取得已启动的沙箱，本节点连接过的直接复用。阻塞调用，在虚拟线程上执行。
     *
     * @param state 序列化后的沙箱状态（{@code SandboxHandle.state()}）
     * @return 已启动的沙箱，{@code start()} 只真正执行一次
     * @throws cn.refinex.meridian.common.error.NotFoundException 沙箱已不存在时
     */
    Sandbox attach(String state);
}
