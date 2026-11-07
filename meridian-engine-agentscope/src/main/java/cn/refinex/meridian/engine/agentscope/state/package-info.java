/**
 * 智能体状态存储：{@link cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore} 实现 AgentScope 的
 * {@code AgentStateStore}，让 AgentScope 在任何路径上的状态写入（调用结束、出错、用户中断、停机、权限上下文替换）
 * 都受租约约束，见架构设计「租约与 fencing」。
 *
 * <p>本包只做 AgentScope 类型与 JSON 文本之间的转换、从 {@code LeaseRegistry} 取租约条件；带租约条件的读写由
 * runtime 实现的 {@code AgentStateRepository} 端口在同一事务中完成。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4。
 */
package cn.refinex.meridian.engine.agentscope.state;
