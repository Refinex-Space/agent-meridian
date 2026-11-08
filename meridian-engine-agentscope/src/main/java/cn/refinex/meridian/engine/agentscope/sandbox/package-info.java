/**
 * {@code sandbox} 档的会话沙箱驱动：{@link cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriver}
 * 经 AgentScope Kubernetes 扩展从 agent-sandbox 预热池认领、恢复、快照、删除沙箱；
 * {@link cn.refinex.meridian.engine.agentscope.sandbox.SingleStartSandbox} 保证 {@code start()} 只执行一次；
 * {@link cn.refinex.meridian.engine.agentscope.sandbox.PlatformSnapshotClient} 把工作区快照存入对象存储。
 * 见架构设计「会话沙箱」「沙箱 Pod 与镜像」。
 */
package cn.refinex.meridian.engine.agentscope.sandbox;
