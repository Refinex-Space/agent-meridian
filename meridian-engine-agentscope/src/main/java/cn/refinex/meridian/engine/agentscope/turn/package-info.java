/**
 * 执行一轮：{@link cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngine} 实现 {@code AgentEngine}，把平台的
 * {@code TurnContext} 与 {@code TurnInput} 翻译为一次 {@code HarnessAgent.streamEvents} 调用，并负责中断与节点下线，
 * 见架构设计「内核适配层」「身份映射」「消息投递」中的中断、「待处理动作」中的结果传递、「崩溃恢复」中的节点优雅下线。
 *
 * <p>本包负责：身份映射、每轮前后的状态缓存清理、权限上下文的翻译与安装、输入翻译、自定义工具注入、智能体实例的
 * 租用与归还。事件的翻译在 {@code events} 包，中间件在 {@code middleware} 包，状态存储在 {@code state} 包。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4、本模块的其他包。
 */
package cn.refinex.meridian.engine.agentscope.turn;
